package com.devlink.server.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.devlink.server.dto.recommendation.GitCoverageReason;
import com.devlink.server.dto.recommendation.GitCoverageState;
import com.devlink.server.dto.recommendation.WorkingObservationState;

/**
 * 추천 한 건을 위해 팀 전체 저장소에서 모은 Git 근거.
 * 사용자 단위로 집계하므로 같은 사용자의 여러 Agent는 같은 근거를 공유한다.
 */
public record RecommendationGitEvidence(
        GitCoverageState coverageState,
        GitCoverageReason coverageReason,
        WorkingObservationState workingState,
        Set<RepoPath> relatedPaths,
        Map<Long, UserEvidence> byUser) {

    public static RecommendationGitEvidence empty(GitCoverageState state, GitCoverageReason reason,
            WorkingObservationState workingState) {
        return new RecommendationGitEvidence(state, reason, workingState, Set.of(), Map.of());
    }

    public UserEvidence forUser(Long userId) {
        return byUser.getOrDefault(userId, UserEvidence.NONE);
    }

    /** 저장소와 상대 경로의 조합. 저장소가 다르면 같은 경로라도 별개 파일이다. */
    public record RepoPath(Long repoId, String relativePath) {
    }

    /**
     * 한 사용자의 관련 파일 근거.
     * workingObservedAt은 관련 파일을 포함한 유효 관측 중 가장 최근 시각, latestCommitAt은 기간 내 관련 커밋 중 최신 시각이다.
     */
    public record UserEvidence(
            Instant workingObservedAt,
            int distinctCommitCount,
            Instant latestCommitAt,
            List<RelatedFile> relatedFiles) {
        public static final UserEvidence NONE = new UserEvidence(null, 0, null, List.of());

        public boolean isWorkingOnRelatedFile() {
            return workingObservedAt != null;
        }
    }

    /** 근거 파일 하나. 현재 작업 중 여부와 해당 경로에 반영된 가장 최근 근거 시각을 가진다. */
    public record RelatedFile(RepoPath path, boolean working, boolean committed, Instant observedAt) {
    }
}
