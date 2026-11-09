package com.devlink.server.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.dto.recommendation.AgentRecommendationRequest;
import com.devlink.server.dto.recommendation.AgentRecommendationResponse;
import com.devlink.server.dto.recommendation.GitCoverageResponse;
import com.devlink.server.dto.recommendation.InsufficientReason;
import com.devlink.server.dto.recommendation.RecommendationCandidateResponse;
import com.devlink.server.dto.recommendation.RecommendationScoresResponse;
import com.devlink.server.dto.recommendation.RecommendationStatus;
import com.devlink.server.dto.recommendation.RelatedFileResponse;
import com.devlink.server.dto.recommendation.RelatedFileSource;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.service.RecommendationGitEvidence.RelatedFile;
import com.devlink.server.service.RecommendationGitEvidence.UserEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 담당 Agent 추천. 질문을 토큰화해 역할 태그 점수를 구하고, 팀 전체 저장소의 Git 근거를 소유자 단위로 더한다.
 * 추천 계산을 완료했지만 근거가 없는 경우는 정상 응답이고, DB 접근 실패만 GIT_ROUTING_UNAVAILABLE이다.
 */
@Service
public class AgentRecommendationService {
    private static final Logger log = LoggerFactory.getLogger(AgentRecommendationService.class);
    private static final Pattern REPO_KEY = Pattern.compile("[0-9a-f]{64}");
    private static final Comparator<ScoredCandidate> RANKING = Comparator
            .comparingDouble(ScoredCandidate::score).reversed()
            .thenComparing(candidate -> candidate.agent().getId());
    private static final Comparator<RelatedFile> RELATED_FILE_ORDER = Comparator
            .comparing(RelatedFile::working).reversed()
            .thenComparing(RelatedFile::observedAt, Comparator.reverseOrder())
            .thenComparing(file -> file.path().repoId())
            .thenComparing(file -> file.path().relativePath());

    private final AgentRepository agents;
    private final TeamAuthorizationService teams;
    private final RecommendationGitEvidenceService gitEvidence;
    private final AgentPresenceService presence;
    private final Clock clock;

    public AgentRecommendationService(AgentRepository agents, TeamAuthorizationService teams,
            RecommendationGitEvidenceService gitEvidence, AgentPresenceService presence,
            ObjectProvider<Clock> clocks) {
        this.agents = agents;
        this.teams = teams;
        this.gitEvidence = gitEvidence;
        this.presence = presence;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
    }

    @Transactional(readOnly = true)
    public AgentRecommendationResponse recommend(Long teamId, AgentPrincipal principal,
            AgentRecommendationRequest request) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        String question = validateQuestion(request);
        if (teamId == null || teamId <= 0 || principal.teamId() != teamId) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        try {
            teams.requireTeamMember(principal.ownerUserId(), teamId);
            return evaluate(teamId, principal.agentId(), question);
        } catch (DataAccessException exception) {
            log.error("Agent recommendation failed for team {}", teamId, exception);
            throw new BusinessException(ErrorCode.GIT_ROUTING_UNAVAILABLE);
        }
    }

    private static String validateQuestion(AgentRecommendationRequest request) {
        if (request == null || request.question() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String question = request.question().strip();
        int length = question.codePointCount(0, question.length());
        if (length == 0 || length > RecommendationScorePolicy.MAX_QUESTION_CODE_POINTS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (request.repoKey() != null && !REPO_KEY.matcher(request.repoKey()).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return question;
    }

    private AgentRecommendationResponse evaluate(Long teamId, long requesterAgentId, String question) {
        // 평가 시각은 요청당 한 번 정해 모든 기간·경과 계산에 동일하게 쓴다.
        Instant evaluatedAt = Instant.now(clock);
        List<String> questionTokens = RecommendationTokenizer.tokenize(question);
        // 소유자가 현재 팀 멤버인 Agent만 후보로 조회하고 요청 Agent 자신을 뺀다.
        List<Agent> candidates = agents.findRecommendationCandidates(teamId).stream()
                .filter(agent -> agent.getId() != requesterAgentId)
                .toList();
        Set<Long> candidateUserIds = new LinkedHashSet<>();
        candidates.forEach(agent -> candidateUserIds.add(agent.getOwnerUserId()));

        RecommendationGitEvidence evidence = gitEvidence.collect(teamId, candidateUserIds, questionTokens, evaluatedAt);
        GitCoverageResponse coverage = new GitCoverageResponse(
                evidence.coverageState(), evidence.coverageReason(), evidence.workingState());
        if (candidates.isEmpty()) {
            return new AgentRecommendationResponse(RecommendationStatus.INSUFFICIENT_EVIDENCE, coverage,
                    evaluatedAt, List.of(), InsufficientReason.NO_ELIGIBLE_AGENTS);
        }

        List<ScoredCandidate> scored = new ArrayList<>();
        for (Agent agent : candidates) {
            scored.add(score(agent, questionTokens, evidence.forUser(agent.getOwnerUserId()), evaluatedAt));
        }
        scored.sort(RANKING);

        List<ScoredCandidate> positive = scored.stream().filter(candidate -> candidate.score() > 0).toList();
        if (positive.isEmpty()) {
            List<RecommendationCandidateResponse> reference = scored.stream()
                    .limit(RecommendationScorePolicy.MAX_CANDIDATES)
                    .map(candidate -> toResponse(candidate, InsufficientReason.NO_MATCHING_EVIDENCE))
                    .toList();
            return new AgentRecommendationResponse(RecommendationStatus.INSUFFICIENT_EVIDENCE, coverage,
                    evaluatedAt, reference, InsufficientReason.NO_MATCHING_EVIDENCE);
        }
        double best = positive.get(0).score();
        List<RecommendationCandidateResponse> selected = positive.stream()
                .filter(candidate -> best - candidate.score() <= RecommendationScorePolicy.CANDIDATE_SCORE_GAP)
                .limit(RecommendationScorePolicy.MAX_CANDIDATES)
                .map(candidate -> toResponse(candidate, null))
                .toList();
        RecommendationStatus status = selected.size() == 1
                ? RecommendationStatus.MATCHED : RecommendationStatus.MULTIPLE_CANDIDATES;
        return new AgentRecommendationResponse(status, coverage, evaluatedAt, selected, null);
    }

    private static ScoredCandidate score(Agent agent, List<String> questionTokens, UserEvidence evidence,
            Instant evaluatedAt) {
        List<String> matchedRoles = RecommendationRoleMatcher.matchedRoles(agent, questionTokens);
        double role = RecommendationScorePolicy.role(matchedRoles.size());
        double working = RecommendationScorePolicy.working(evidence.isWorkingOnRelatedFile());
        double frequency = RecommendationScorePolicy.frequency(evidence.distinctCommitCount());
        double recency = RecommendationScorePolicy.recency(evidence.latestCommitAt(), evaluatedAt);
        return new ScoredCandidate(agent, matchedRoles, evidence, role, working, frequency, recency);
    }

    private RecommendationCandidateResponse toResponse(ScoredCandidate candidate, InsufficientReason reason) {
        UserEvidence evidence = candidate.evidence();
        List<RelatedFileResponse> relatedFiles = evidence.relatedFiles().stream()
                .sorted(RELATED_FILE_ORDER)
                .limit(RecommendationScorePolicy.MAX_RELATED_FILES)
                .map(AgentRecommendationService::toResponse)
                .toList();
        RecommendationScoresResponse scores = new RecommendationScoresResponse(
                RecommendationScorePolicy.display(candidate.role()),
                RecommendationScorePolicy.display(candidate.git()),
                RecommendationScorePolicy.display(candidate.working()),
                RecommendationScorePolicy.display(candidate.frequency()),
                RecommendationScorePolicy.display(candidate.recency()));
        Agent agent = candidate.agent();
        // 점수로 선정된 최대 5명에 대해서만 실제 접속 상태를 조회한다. 점수·순위에는 반영하지 않는다.
        return new RecommendationCandidateResponse(agent.getId(), agent.getName(), agent.getPart(),
                presence.connectionStatus(agent),
                RecommendationScorePolicy.display(candidate.score()), scores, candidate.matchedRoles(), relatedFiles,
                evidence.latestCommitAt(), evidence.workingObservedAt(), reason);
    }

    private static RelatedFileResponse toResponse(RelatedFile file) {
        List<RelatedFileSource> sources = new ArrayList<>(2);
        if (file.working()) {
            sources.add(RelatedFileSource.WORKING);
        }
        if (file.committed()) {
            sources.add(RelatedFileSource.COMMIT);
        }
        return new RelatedFileResponse(file.path().repoId(), file.path().relativePath(), List.copyOf(sources),
                file.observedAt());
    }

    private record ScoredCandidate(Agent agent, List<String> matchedRoles, UserEvidence evidence,
            double role, double working, double frequency, double recency) {
        double git() {
            return working + frequency + recency;
        }

        double score() {
            return role + git();
        }
    }
}
