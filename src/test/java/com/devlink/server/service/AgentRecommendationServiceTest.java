package com.devlink.server.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.AgentPart;
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
import com.devlink.server.service.RecommendationGitEvidence.RelatedFile;
import com.devlink.server.service.RecommendationGitEvidence.RepoPath;
import com.devlink.server.service.RecommendationGitEvidence.UserEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
    private final AgentRecommendationService service = new AgentRecommendationService(agents, teams, evidence,
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
                        200L, new UserEvidence(observed, 1, twoDaysAgo, List.of(
                                new RelatedFile(PATH, true, true, observed),
                                new RelatedFile(new RepoPath(12L, "src/auth/TokenIssuer.java"), false, true,
                                        twoDaysAgo))),
                        300L, new UserEvidence(observed, 2, NOW.minus(Duration.ofDays(28)), List.of(
                                new RelatedFile(PATH, true, true, observed))),
                        400L, new UserEvidence(null, 2, NOW.minus(Duration.ofDays(14)), List.of(
                                new RelatedFile(PATH, false, true, NOW.minus(Duration.ofDays(14))))))));

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
                Set.of(PATH), Map.of(200L, new UserEvidence(NOW, 5, NOW, List.of(new RelatedFile(PATH, true, true, NOW))))));

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
        List<Agent> team = new java.util.ArrayList<>();
        for (long id = 9; id >= 3; id--) {
            team.add(agent(id, id * 10, "Agent " + id, List.of("인증")));
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
