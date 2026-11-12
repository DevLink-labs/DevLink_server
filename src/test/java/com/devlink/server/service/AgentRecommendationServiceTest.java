package com.devlink.server.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.AgentPart;
import com.devlink.server.domain.agent.AgentStatus;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.dto.recommendation.AgentRecommendationRequest;
import com.devlink.server.dto.recommendation.AgentRecommendationResponse;
import com.devlink.server.dto.recommendation.GitCoverageReason;
import com.devlink.server.dto.recommendation.GitCoverageState;
import com.devlink.server.dto.recommendation.InsufficientReason;
import com.devlink.server.dto.recommendation.RecommendationCandidateResponse;
import com.devlink.server.dto.recommendation.RecommendationStatus;
import com.devlink.server.dto.recommendation.RelatedFileSource;
import com.devlink.server.dto.recommendation.WorkingObservationState;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.service.RecommendationGitEvidence.RelatedFile;
import com.devlink.server.service.RecommendationGitEvidence.RepoPath;
import com.devlink.server.service.RecommendationGitEvidence.UserEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentRecommendationServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");
    private static final long TEAM = 7L;
    private static final AgentPrincipal REQUESTER = new AgentPrincipal(10L, 100L, TEAM);
    private static final String QUESTION = "로그인을 처리하는 인증 흐름에서 refreshToken을 어떻게 반환해?";
    private static final RepoPath PATH = new RepoPath(12L, "src/auth/RefreshTokenController.java");

    private final AgentRepository agents = mock(AgentRepository.class);
    private final TeamAuthorizationService teams = mock(TeamAuthorizationService.class);
    private final RecommendationGitEvidenceService evidence = mock(RecommendationGitEvidenceService.class);
    private final ConnectionRegistry connections = mock(ConnectionRegistry.class);
    private final AgentPresenceService presence = spy(new AgentPresenceService(
            agents, connections, mock(ApplicationEventPublisher.class), 40000));
    private final AgentRecommendationService service = new AgentRecommendationService(agents, teams, evidence, presence,
            fixedClock());

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "　"})
    void blankQuestionIsRejectedBeforeAnyLookup(String question) {
        assertError(() -> service.recommend(TEAM, REQUESTER, new AgentRecommendationRequest(question, null)),
                ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(agents, teams, evidence);
    }

    @Test
    void questionLengthIsCountedInCodePoints() {
        String thousandEmoji = "😀".repeat(1000);
        stubTeam(List.of());
        stubEvidence(RecommendationGitEvidence.empty(GitCoverageState.NOT_COLLECTED,
                GitCoverageReason.NO_TEAM_REPOSITORIES, WorkingObservationState.NOT_COLLECTED));

        assertThat(service.recommend(TEAM, REQUESTER, new AgentRecommendationRequest("  " + thousandEmoji + "  ", null))
                .status()).isEqualTo(RecommendationStatus.INSUFFICIENT_EVIDENCE);
        assertError(() -> service.recommend(TEAM, REQUESTER, new AgentRecommendationRequest(thousandEmoji + "a", null)),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void malformedRepoKeyIsRejected() {
        assertError(() -> service.recommend(TEAM, REQUESTER, new AgentRecommendationRequest(QUESTION, "ABC")),
                ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(agents, teams, evidence);
    }

    @Test
    void otherTeamIsDeniedWithoutLookingUpCandidates() {
        assertError(() -> service.recommend(TEAM + 1, REQUESTER, new AgentRecommendationRequest(QUESTION, null)),
                ErrorCode.ACCESS_DENIED);
        verifyNoInteractions(agents, teams, evidence);
    }

    @Test
    void missingPrincipalRequiresAuthentication() {
        assertError(() -> service.recommend(TEAM, null, new AgentRecommendationRequest(QUESTION, null)),
                ErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Test
    void onlyRequesterInTeamYieldsNoEligibleAgents() {
        stubTeam(List.of(agent(10L, 100L, "Me", List.of("로그인"))));
        stubEvidence(RecommendationGitEvidence.empty(GitCoverageState.NOT_COLLECTED,
                GitCoverageReason.NO_TEAM_REPOSITORIES, WorkingObservationState.NOT_COLLECTED));

        AgentRecommendationResponse response = service.recommend(TEAM, REQUESTER,
                new AgentRecommendationRequest(QUESTION, "a".repeat(64)));

        assertThat(response.status()).isEqualTo(RecommendationStatus.INSUFFICIENT_EVIDENCE);
        assertThat(response.insufficientReason()).isEqualTo(InsufficientReason.NO_ELIGIBLE_AGENTS);
        assertThat(response.candidates()).isEmpty();
        assertThat(response.evaluatedAt()).isEqualTo(NOW);
        assertThat(response.gitCoverage().state()).isEqualTo(GitCoverageState.NOT_COLLECTED);
        assertThat(response.gitCoverage().reason()).isEqualTo(GitCoverageReason.NO_TEAM_REPOSITORIES);
    }

    @Test
    void zeroScoreCandidatesAreReturnedAsReferenceWithNoMatchingEvidence() {
        stubTeam(List.of(
                agent(10L, 100L, "Me", List.of("로그인")),
                agent(23L, 300L, "결제 Agent", List.of("결제")),
                agent(21L, 200L, "배포 Agent", List.of())));
        stubEvidence(RecommendationGitEvidence.empty(GitCoverageState.AVAILABLE, null, WorkingObservationState.STALE));

        AgentRecommendationResponse response = service.recommend(TEAM, REQUESTER,
                new AgentRecommendationRequest(QUESTION, null));

        assertThat(response.status()).isEqualTo(RecommendationStatus.INSUFFICIENT_EVIDENCE);
        assertThat(response.insufficientReason()).isEqualTo(InsufficientReason.NO_MATCHING_EVIDENCE);
        assertThat(response.candidates()).extracting(RecommendationCandidateResponse::agentId).containsExactly(21L, 23L);
        assertThat(response.candidates()).allSatisfy(candidate -> {
            assertThat(candidate.score()).isZero();
            assertThat(candidate.insufficientReason()).isEqualTo(InsufficientReason.NO_MATCHING_EVIDENCE);
            assertThat(candidate.relatedFiles()).isEmpty();
            assertThat(candidate.latestCommitAt()).isNull();
        });
        assertThat(response.gitCoverage().workingState()).isEqualTo(WorkingObservationState.STALE);
    }

    @Test
    void designExampleReturnsCandidatesWithinFivePointsOfBest() {
        // 설계 문서 5.3·2차 설명 예시: B 67.06, C 63.50, D 51.00 → B·C만 반환
        Agent b = agent(21L, 200L, "인증 Agent", List.of("로그인", "인증"));
        Agent c = agent(22L, 300L, "토큰 Agent", List.of("로그인", "인증"));
        Agent d = agent(23L, 400L, "세션 Agent", List.of("로그인", "인증"));
        stubTeam(List.of(agent(10L, 100L, "Me", List.of()), b, c, d));
        Instant twoDaysAgo = NOW.minus(Duration.ofDays(2));
        Instant observed = NOW.minusSeconds(20);
        stubEvidence(new RecommendationGitEvidence(GitCoverageState.AVAILABLE, null, WorkingObservationState.FRESH,
                Set.of(PATH), Map.of(
                        200L, directEvidence(observed, 1, twoDaysAgo, List.of(
                                new RelatedFile(PATH, true, true, observed, false),
                                new RelatedFile(new RepoPath(12L, "src/auth/TokenIssuer.java"), false, true,
                                        twoDaysAgo, false))),
                        300L, directEvidence(observed, 2, NOW.minus(Duration.ofDays(28)), List.of(
                                new RelatedFile(PATH, true, true, observed, false))),
                        400L, directEvidence(null, 2, NOW.minus(Duration.ofDays(14)), List.of(
                                new RelatedFile(PATH, false, true, NOW.minus(Duration.ofDays(14)), false))))));

        AgentRecommendationResponse response = service.recommend(TEAM, REQUESTER,
                new AgentRecommendationRequest(QUESTION, null));

        assertThat(response.status()).isEqualTo(RecommendationStatus.MULTIPLE_CANDIDATES);
        assertThat(response.insufficientReason()).isNull();
        assertThat(response.candidates()).extracting(RecommendationCandidateResponse::agentId).containsExactly(21L, 22L);

        RecommendationCandidateResponse first = response.candidates().get(0);
        assertThat(first.score()).isEqualTo(67.06);
        assertThat(first.scores().role()).isEqualTo(40.0);
        assertThat(first.scores().working()).isEqualTo(15.0);
        assertThat(first.scores().frequency()).isEqualTo(3.0);
        assertThat(first.scores().recency()).isEqualTo(9.06);
        assertThat(first.scores().git()).isEqualTo(27.06);
        assertThat(first.matchedRoles()).containsExactly("로그인", "인증");
        assertThat(first.latestCommitAt()).isEqualTo(twoDaysAgo);
        assertThat(first.workingObservedAt()).isEqualTo(observed);
        assertThat(first.insufficientReason()).isNull();
        assertThat(first.relatedFiles()).hasSize(2);
        assertThat(first.relatedFiles().get(0).relativePath()).isEqualTo(PATH.relativePath());
        assertThat(first.relatedFiles().get(0).sources())
                .containsExactly(RelatedFileSource.WORKING, RelatedFileSource.COMMIT);
        assertThat(first.relatedFiles().get(1).sources()).containsExactly(RelatedFileSource.COMMIT);

        assertThat(response.candidates().get(1).score()).isEqualTo(63.5);
        assertThat(response.candidates().get(1).part()).isEqualTo(AgentPart.BACKEND);
    }

    @Test
    void singlePositiveCandidateIsMatchedAndSameOwnerAgentsShareGitScore() {
        Agent tagged = agent(31L, 200L, "태그 Agent", List.of("인증"));
        Agent untagged = agent(32L, 200L, "무태그 Agent", List.of());
        stubTeam(List.of(tagged, untagged));
        stubEvidence(new RecommendationGitEvidence(GitCoverageState.AVAILABLE, null, WorkingObservationState.FRESH,
                Set.of(PATH), Map.of(200L, directEvidence(NOW, 5, NOW, List.of(new RelatedFile(PATH, true, true, NOW, false))))));

        AgentRecommendationResponse response = service.recommend(TEAM, REQUESTER,
                new AgentRecommendationRequest(QUESTION, null));

        // 태그 Agent 60점, 무태그 Agent 40점 → 5점 차이를 넘어 한 명만 반환
        assertThat(response.status()).isEqualTo(RecommendationStatus.MATCHED);
        assertThat(response.candidates()).extracting(RecommendationCandidateResponse::agentId).containsExactly(31L);
        assertThat(response.candidates().get(0).score()).isEqualTo(60.0);
        assertThat(response.candidates().get(0).scores().git()).isEqualTo(40.0);
    }

    @Test
    void tiesAreOrderedByAgentIdAndLimitedToFive() {
        List<Agent> team = new ArrayList<>();
        for (long id = 9; id >= 3; id--) {
            Agent candidate = agent(id, id * 10, "Agent " + id, List.of("인증"));
            candidate.markOnline(LocalDateTime.now());
            team.add(candidate);
        }
        stubTeam(team);
        stubEvidence(RecommendationGitEvidence.empty(GitCoverageState.NOT_COLLECTED,
                GitCoverageReason.NO_RETAINED_OBSERVATIONS, WorkingObservationState.NOT_COLLECTED));

        AgentRecommendationResponse response = service.recommend(TEAM, REQUESTER,
                new AgentRecommendationRequest(QUESTION, null));

        assertThat(response.status()).isEqualTo(RecommendationStatus.MULTIPLE_CANDIDATES);
        assertThat(response.candidates()).extracting(RecommendationCandidateResponse::agentId)
                .containsExactly(3L, 4L, 5L, 6L, 7L);
        assertThat(response.gitCoverage().reason()).isEqualTo(GitCoverageReason.NO_RETAINED_OBSERVATIONS);
        // 후보가 더 많아도 응답으로 선정한 5명만 상태를 확인하며 추가 DB 조회는 없다.
        for (Agent agent : team) {
            if (agent.getId() <= 7) {
                verify(presence).connectionStatus(agent);
                verify(connections).hasActiveConnection(agent.getId().toString(), Duration.ofSeconds(40));
            }
        }
        verifyNoMoreInteractions(presence);
        verifyNoMoreInteractions(connections);
        verify(agents).findRecommendationCandidates(TEAM);
        verifyNoMoreInteractions(agents);
    }

    @Test
    void connectionChangesDoNotAffectScoresOrCandidateSelection() {
        Agent first = agent(21L, 200L, "인증 Agent", List.of("인증"));
        Agent second = agent(22L, 300L, "토큰 Agent", List.of("인증"));
        first.markOnline(LocalDateTime.now());
        stubTeam(List.of(first, second));
        stubEvidence(RecommendationGitEvidence.empty(GitCoverageState.NOT_COLLECTED,
                GitCoverageReason.NO_TEAM_REPOSITORIES, WorkingObservationState.NOT_COLLECTED));
        when(connections.hasActiveConnection("21", Duration.ofSeconds(40))).thenReturn(true);

        AgentRecommendationResponse online = service.recommend(TEAM, REQUESTER,
                new AgentRecommendationRequest(QUESTION, null));
        when(connections.hasActiveConnection("21", Duration.ofSeconds(40))).thenReturn(false);
        AgentRecommendationResponse offline = service.recommend(TEAM, REQUESTER,
                new AgentRecommendationRequest(QUESTION, null));

        assertThat(online.candidates()).extracting(RecommendationCandidateResponse::connectionStatus)
                .containsExactly(AgentStatus.ONLINE, AgentStatus.OFFLINE);
        assertThat(offline.candidates()).extracting(RecommendationCandidateResponse::connectionStatus)
                .containsExactly(AgentStatus.OFFLINE, AgentStatus.OFFLINE);
        assertThat(offline.candidates()).usingRecursiveComparison().ignoringFields("connectionStatus")
                .isEqualTo(online.candidates());
        assertThat(offline.status()).isEqualTo(online.status());
    }

    @Test
    void zeroScoreReferenceCandidatesAlsoUseCurrentConnectionStatus() {
        Agent reference = agent(21L, 200L, "결제 Agent", List.of("결제"));
        reference.markOnline(LocalDateTime.now());
        stubTeam(List.of(reference));
        stubEvidence(RecommendationGitEvidence.empty(GitCoverageState.NOT_COLLECTED,
                GitCoverageReason.NO_TEAM_REPOSITORIES, WorkingObservationState.NOT_COLLECTED));
        when(connections.hasActiveConnection("21", Duration.ofSeconds(40))).thenReturn(true);

        AgentRecommendationResponse response = service.recommend(TEAM, REQUESTER,
                new AgentRecommendationRequest(QUESTION, null));

        assertThat(response.status()).isEqualTo(RecommendationStatus.INSUFFICIENT_EVIDENCE);
        assertThat(response.candidates().get(0).connectionStatus()).isEqualTo(AgentStatus.ONLINE);
        assertThat(response.candidates().get(0).score()).isZero();
    }

    @Test
    void dataAccessFailureBecomesGitRoutingUnavailable() {
        stubTeam(List.of(agent(21L, 200L, "인증 Agent", List.of("인증"))));
        when(evidence.collect(eq(TEAM), any(), anyList(), any())).thenThrow(new QueryTimeoutException("timeout"));

        assertError(() -> service.recommend(TEAM, REQUESTER, new AgentRecommendationRequest(QUESTION, null)),
                ErrorCode.GIT_ROUTING_UNAVAILABLE);
    }

    @Test
    void membershipFailurePropagatesAsIs() {
        when(teams.requireTeamMember(100L, TEAM)).thenThrow(new BusinessException(ErrorCode.ACCESS_DENIED));

        assertError(() -> service.recommend(TEAM, REQUESTER, new AgentRecommendationRequest(QUESTION, null)),
                ErrorCode.ACCESS_DENIED);
        verifyNoInteractions(agents, evidence);
    }

    @Test
    void workingIndirectFileIsFirstEvenWhenDirectCommitsFillTheDisplayLimit() {
        Instant oldCommit = NOW.minus(Duration.ofDays(80));
        List<RelatedFile> files = new ArrayList<>();
        for (int index = 0; index < RecommendationScorePolicy.MAX_RELATED_FILES; index++) {
            files.add(new RelatedFile(new RepoPath(12L, "src/auth/Direct" + index + ".java"),
                    false, true, oldCommit, false));
        }
        RepoPath indirectPath = new RepoPath(12L, "src/web/CheckoutController.java");
        files.add(new RelatedFile(indirectPath, true, false, NOW, true));
        UserEvidence git = new UserEvidence(NOW, RecommendationScorePolicy.MAX_RELATED_FILES, oldCommit, files,
                RecommendationScorePolicy.INDIRECT_WEIGHT, RecommendationScorePolicy.MAX_RELATED_FILES,
                oldCommit, 1.0);

        var candidate = recommendWithEvidence(git);

        assertThat(candidate.relatedFiles()).hasSize(RecommendationScorePolicy.MAX_RELATED_FILES);
        assertThat(candidate.relatedFiles().getFirst().relativePath()).isEqualTo(indirectPath.relativePath());
        assertThat(candidate.relatedFiles().getFirst().sources()).contains(RelatedFileSource.WORKING);
        assertThat(candidate.workingObservedAt()).isEqualTo(NOW);
        assertThat(candidate.scores().working())
                .isEqualTo(RecommendationScorePolicy.working(RecommendationScorePolicy.INDIRECT_WEIGHT));
        assertThat(candidate.scores().frequency())
                .isEqualTo(RecommendationScorePolicy.frequency(RecommendationScorePolicy.MAX_RELATED_FILES));
    }

    @Test
    void workingDirectFilePrecedesMoreRecentWorkingIndirectFile() {
        RepoPath indirectPath = new RepoPath(12L, "src/web/CheckoutController.java");
        List<RelatedFile> files = List.of(
                new RelatedFile(indirectPath, true, false, NOW, true),
                new RelatedFile(PATH, true, false, NOW.minusSeconds(30), false));
        UserEvidence git = new UserEvidence(NOW, 0, null, files, 1.0, 0.0, null, 0.0);

        var candidate = recommendWithEvidence(git);

        assertThat(candidate.relatedFiles()).extracting(file -> file.relativePath())
                .containsExactly(PATH.relativePath(), indirectPath.relativePath());
        assertThat(candidate.relatedFiles()).allSatisfy(file ->
                assertThat(file.sources()).contains(RelatedFileSource.WORKING));
    }

    @Test
    void committedFilesUseDirectWeightBeforeRecencyAndRecencyWithinEachWeight() {
        RepoPath oldDirect = new RepoPath(12L, "src/auth/OldDirect.java");
        RepoPath recentDirect = new RepoPath(12L, "src/auth/RecentDirect.java");
        RepoPath oldIndirect = new RepoPath(12L, "src/web/OldIndirect.java");
        RepoPath recentIndirect = new RepoPath(12L, "src/web/RecentIndirect.java");
        Instant oldCommit = NOW.minus(Duration.ofDays(80));
        List<RelatedFile> files = List.of(
                new RelatedFile(recentIndirect, false, true, NOW, true),
                new RelatedFile(oldDirect, false, true, oldCommit, false),
                new RelatedFile(oldIndirect, false, true, oldCommit, true),
                new RelatedFile(recentDirect, false, true, NOW.minusSeconds(30), false));

        var candidate = recommendWithEvidence(directEvidence(null, files.size(), NOW, files));

        assertThat(candidate.relatedFiles()).extracting(file -> file.relativePath())
                .containsExactly(recentDirect.relativePath(), oldDirect.relativePath(),
                        recentIndirect.relativePath(), oldIndirect.relativePath());
    }

    private RecommendationCandidateResponse recommendWithEvidence(UserEvidence git) {
        stubTeam(List.of(agent(21L, 200L, "Candidate", List.of())));
        Set<RepoPath> paths = git.relatedFiles().stream().map(RelatedFile::path)
                .collect(Collectors.toSet());
        stubEvidence(new RecommendationGitEvidence(GitCoverageState.AVAILABLE, null,
                git.workingObservedAt() != null ? WorkingObservationState.FRESH : WorkingObservationState.NOT_COLLECTED,
                paths, Map.of(200L, git)));
        return service.recommend(TEAM, REQUESTER, new AgentRecommendationRequest(QUESTION, null))
                .candidates().getFirst();
    }

    private static UserEvidence directEvidence(Instant workingObservedAt, int distinctCommitCount,
            Instant latestCommitAt, List<RelatedFile> relatedFiles) {
        return new UserEvidence(workingObservedAt, distinctCommitCount, latestCommitAt, relatedFiles,
                workingObservedAt == null ? 0.0 : 1.0, distinctCommitCount, latestCommitAt,
                latestCommitAt == null ? 0.0 : 1.0);
    }

    private void stubTeam(List<Agent> teamAgents) {
        when(agents.findRecommendationCandidates(TEAM)).thenReturn(teamAgents);
    }

    private void stubEvidence(RecommendationGitEvidence stub) {
        when(evidence.collect(eq(TEAM), any(), anyList(), any())).thenReturn(stub);
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private static Agent agent(long id, long ownerUserId, String name, List<String> roles) {
        Agent agent = Agent.create(TEAM, ownerUserId, name, null, ToolType.CODEX, "project", AgentPart.BACKEND, roles);
        ReflectionTestUtils.setField(agent, "id", id);
        return agent;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<Clock> fixedClock() {
        ObjectProvider<Clock> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
        return provider;
    }
}
