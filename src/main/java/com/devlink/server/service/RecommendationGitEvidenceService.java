package com.devlink.server.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.devlink.server.domain.gitrepo.TeamGitRepository;
import com.devlink.server.domain.gitrepo.WorkingFile;
import com.devlink.server.domain.gitrepo.WorkingFileEntry;
import com.devlink.server.dto.recommendation.GitCoverageReason;
import com.devlink.server.dto.recommendation.GitCoverageState;
import com.devlink.server.dto.recommendation.WorkingObservationState;
import com.devlink.server.repository.FileDependencyRepository;
import com.devlink.server.repository.CommitFileRepository;
import com.devlink.server.repository.CommitFileRepository.CommitEvidenceView;
import com.devlink.server.repository.CommitFileRepository.CommitPathView;
import com.devlink.server.repository.RepositoryCommitRepository;
import com.devlink.server.repository.TeamGitRepositoryRepository;
import com.devlink.server.repository.WorkingFileRepository;
import com.devlink.server.service.RecommendationGitEvidence.RelatedFile;
import com.devlink.server.service.RecommendationGitEvidence.RepoPath;
import com.devlink.server.service.RecommendationGitEvidence.UserEvidence;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 직접 관련 경로와 한 단계 importer를 찾고 사용자별 가중 Git 근거를 집계한다.
 */
@Service
public class RecommendationGitEvidenceService {
    private final TeamGitRepositoryRepository repositories;
    private final RepositoryCommitRepository commits;
    private final CommitFileRepository commitFiles;
    private final WorkingFileRepository workingFiles;
    private final FileDependencyRepository dependencies;

    public RecommendationGitEvidenceService(TeamGitRepositoryRepository repositories,
            RepositoryCommitRepository commits, CommitFileRepository commitFiles,
            WorkingFileRepository workingFiles, FileDependencyRepository dependencies) {
        this.repositories = repositories;
        this.commits = commits;
        this.commitFiles = commitFiles;
        this.workingFiles = workingFiles;
        this.dependencies = dependencies;
    }

    @Transactional(readOnly = true)
    public RecommendationGitEvidence collect(Long teamId, Collection<Long> candidateUserIds,
            List<String> questionTokens, Instant evaluatedAt) {
        List<Long> repoIds = repositories.findByTeamId(teamId).stream().map(TeamGitRepository::getId).toList();
        if (repoIds.isEmpty()) {
            return RecommendationGitEvidence.empty(GitCoverageState.NOT_COLLECTED,
                    GitCoverageReason.NO_TEAM_REPOSITORIES, WorkingObservationState.NOT_COLLECTED);
        }
        boolean retained = commits.existsByTeamId(teamId) || workingFiles.existsByIdRepoIdIn(repoIds);
        GitCoverageState coverageState = retained ? GitCoverageState.AVAILABLE : GitCoverageState.NOT_COLLECTED;
        GitCoverageReason coverageReason = retained ? null : GitCoverageReason.NO_RETAINED_OBSERVATIONS;
        if (candidateUserIds.isEmpty()) {
            return RecommendationGitEvidence.empty(coverageState, coverageReason, WorkingObservationState.NOT_COLLECTED);
        }

        Instant from = evaluatedAt.minus(RecommendationScorePolicy.COMMIT_WINDOW);
        List<WorkingFile> observations = workingFiles.findByIdRepoIdInAndIdUserIdIn(repoIds, candidateUserIds);
        WorkingObservationState workingState = workingState(observations, evaluatedAt);

        Set<String> questionPathTokens = RecommendationTokenizer.pathMatchableTokens(questionTokens);
        Set<RepoPath> relatedPaths = findRelatedPaths(teamId, observations, questionPathTokens, from, evaluatedAt);
        if (relatedPaths.isEmpty()) {
            return RecommendationGitEvidence.empty(coverageState, coverageReason, workingState);
        }

        Set<RepoPath> indirectPaths = expandImporters(teamId, relatedPaths);
        relatedPaths.addAll(indirectPaths);
        Map<Long, UserAccumulator> accumulators = new HashMap<>();
        collectWorkingEvidence(observations, relatedPaths, evaluatedAt, accumulators);
        collectCommitEvidence(teamId, candidateUserIds, relatedPaths, from, evaluatedAt, accumulators);

        Map<Long, UserEvidence> byUser = new HashMap<>();
        accumulators.forEach((userId, accumulator) -> byUser.put(userId, accumulator.build(indirectPaths, evaluatedAt)));
        return new RecommendationGitEvidence(GitCoverageState.AVAILABLE, null, workingState,
                Set.copyOf(relatedPaths), Map.copyOf(byUser));
    }

    /** 질문과 완전 토큰을 하나 이상 공유하는 경로. 커밋 이력과 유효 작업 관측의 경로를 모두 후보로 본다. */
    private Set<RepoPath> findRelatedPaths(Long teamId, List<WorkingFile> observations,
            Set<String> questionPathTokens, Instant from, Instant to) {
        if (questionPathTokens.isEmpty()) {
            return Set.of();
        }
        Set<RepoPath> related = new HashSet<>();
        for (CommitPathView row : commitFiles.findTeamPathsInWindow(teamId, from, to)) {
            addIfRelated(related, new RepoPath(row.getRepoId(), row.getRelativePath()), questionPathTokens);
        }
        for (WorkingFile observation : observations) {
            if (observation.isExpired(to)) {
                continue;
            }
            for (WorkingFileEntry entry : observation.getFiles()) {
                addIfRelated(related, new RepoPath(observation.getRepoId(), entry.relativePath()), questionPathTokens);
            }
        }
        return related;
    }

    // 원래 직접 관련 경로에서 한 단계만 확장하며 직접 매칭을 우선한다.
    private Set<RepoPath> expandImporters(Long teamId, Set<RepoPath> directPaths) {
        Map<Long, Set<String>> byRepo = new HashMap<>();
        directPaths.forEach(path -> byRepo.computeIfAbsent(path.repoId(), id -> new HashSet<>())
                .add(path.relativePath()));
        Set<RepoPath> indirectPaths = new HashSet<>();
        byRepo.forEach((repoId, paths) -> {
            Map<String, Set<String>> importersByTarget = new HashMap<>();
            for (List<String> chunk : pathChunks(paths)) {
                for (var edge : dependencies.findImportingFiles(teamId, repoId, chunk)) {
                    importersByTarget.computeIfAbsent(edge.getTargetPath(), target -> new HashSet<>())
                            .add(edge.getSourcePath());
                }
            }
            importersByTarget.values().forEach(importerPaths -> {
                if (importerPaths.size() > RecommendationScorePolicy.MAX_IMPORTERS_PER_DIRECT_FILE) {
                    return;
                }
                for (String importerPath : importerPaths) {
                    RepoPath importer = new RepoPath(repoId, importerPath);
                    if (!directPaths.contains(importer)) {
                        indirectPaths.add(importer);
                    }
                }
            });
        });
        return indirectPaths;
    }

    private static void addIfRelated(Set<RepoPath> related, RepoPath path, Set<String> questionPathTokens) {
        if (related.contains(path)) {
            return;
        }
        for (String token : RecommendationTokenizer.tokenizePath(path.relativePath())) {
            if (questionPathTokens.contains(token)) {
                related.add(path);
                return;
            }
        }
    }

    private static WorkingObservationState workingState(List<WorkingFile> observations, Instant evaluatedAt) {
        if (observations.isEmpty()) {
            return WorkingObservationState.NOT_COLLECTED;
        }
        boolean fresh = observations.stream().anyMatch(observation -> !observation.isExpired(evaluatedAt));
        return fresh ? WorkingObservationState.FRESH : WorkingObservationState.STALE;
    }

    private static void collectWorkingEvidence(List<WorkingFile> observations, Set<RepoPath> relatedPaths,
            Instant evaluatedAt, Map<Long, UserAccumulator> accumulators) {
        for (WorkingFile observation : observations) {
            if (observation.isExpired(evaluatedAt)) {
                continue;
            }
            for (WorkingFileEntry entry : observation.getFiles()) {
                RepoPath path = new RepoPath(observation.getRepoId(), entry.relativePath());
                if (relatedPaths.contains(path)) {
                    accumulators.computeIfAbsent(observation.getUserId(), id -> new UserAccumulator())
                            .working(path, observation.getObservedAt());
                }
            }
        }
    }

    private void collectCommitEvidence(Long teamId, Collection<Long> candidateUserIds, Set<RepoPath> relatedPaths,
            Instant from, Instant to, Map<Long, UserAccumulator> accumulators) {
        Set<String> paths = new HashSet<>();
        relatedPaths.forEach(path -> paths.add(path.relativePath()));
        for (List<String> chunk : pathChunks(paths)) {
            for (CommitEvidenceView row : commitFiles.findEvidenceInWindow(
                    teamId, candidateUserIds, from, to, chunk)) {
                RepoPath path = new RepoPath(row.getRepoId(), row.getRelativePath());
                if (!relatedPaths.contains(path)) {
                    continue;
                }
                accumulators.computeIfAbsent(row.getAuthorUserId(), id -> new UserAccumulator())
                        .commit(path, row.getRepoId(), row.getCommitSha(), row.getCommittedAt());
            }
        }
    }

    private static List<List<String>> pathChunks(Set<String> paths) {
        List<String> sorted = paths.stream().sorted().toList();
        List<List<String>> chunks = new ArrayList<>();
        int chunkSize = RecommendationScorePolicy.PATH_QUERY_CHUNK_SIZE;
        for (int start = 0; start < sorted.size(); start += chunkSize) {
            chunks.add(sorted.subList(start, Math.min(start + chunkSize, sorted.size())));
        }
        return chunks;
    }

    private static Instant latest(Instant current, Instant candidate) {
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    /** 사용자 한 명의 근거를 모으는 가변 누적기. 고유 커밋은 (repoId, commitSha) 기준으로 센다. */
    private static final class UserAccumulator {
        private final Map<RepoPath, FileAccumulator> files = new LinkedHashMap<>();
        private final Map<String, CommitAccumulator> distinctCommits = new HashMap<>();
        private Instant workingObservedAt;
        private Instant latestCommitAt;

        void working(RepoPath path, Instant observedAt) {
            workingObservedAt = latest(workingObservedAt, observedAt);
            files.computeIfAbsent(path, FileAccumulator::new).working(observedAt);
        }

        void commit(RepoPath path, Long repoId, String sha, Instant committedAt) {
            distinctCommits.computeIfAbsent(repoId + ":" + sha, key -> new CommitAccumulator(committedAt))
                    .paths.add(path);
            latestCommitAt = latest(latestCommitAt, committedAt);
            files.computeIfAbsent(path, FileAccumulator::new).commit(committedAt);
        }

        UserEvidence build(Set<RepoPath> indirectPaths, Instant evaluatedAt) {
            List<RelatedFile> related = new ArrayList<>();
            files.values().forEach(file -> related.add(file.build(indirectPaths.contains(file.path))));
            double workingWeight = related.stream().filter(RelatedFile::working)
                    .mapToDouble(RelatedFile::weight).max().orElse(0.0);
            int directCommitCount = 0;
            double indirectCommitWeight = 0.0;
            Instant strongestAt = null;
            double strongestWeight = 0.0;
            double strongestScore = -1.0;
            for (CommitAccumulator commit : distinctCommits.values()) {
                double weight = commit.paths.stream().mapToDouble(path ->
                        indirectPaths.contains(path) ? RecommendationScorePolicy.INDIRECT_WEIGHT : 1.0)
                        .max().orElse(0.0);
                if (weight == 1.0) {
                    directCommitCount++;
                } else {
                    indirectCommitWeight += weight;
                }
                double score = weight * RecommendationScorePolicy.recency(commit.at, evaluatedAt);
                if (score > strongestScore || (score == strongestScore
                        && (strongestAt == null || commit.at.isAfter(strongestAt)))) {
                    strongestScore = score;
                    strongestAt = commit.at;
                    strongestWeight = weight;
                }
            }
            double weightedCommitCount = directCommitCount
                    + Math.min(indirectCommitWeight, RecommendationScorePolicy.MAX_INDIRECT_COMMIT_WEIGHT);
            return new UserEvidence(workingObservedAt, distinctCommits.size(), latestCommitAt,
                    List.copyOf(related), workingWeight, weightedCommitCount, strongestAt, strongestWeight);
        }
    }

    private static final class CommitAccumulator {
        private final Instant at;
        private final Set<RepoPath> paths = new HashSet<>();

        CommitAccumulator(Instant at) {
            this.at = at;
        }
    }

    private static final class FileAccumulator {
        private final RepoPath path;
        private boolean working;
        private boolean committed;
        private Instant observedAt;

        FileAccumulator(RepoPath path) {
            this.path = path;
        }

        void working(Instant at) {
            working = true;
            observedAt = latest(observedAt, at);
        }

        void commit(Instant at) {
            committed = true;
            observedAt = latest(observedAt, at);
        }

        RelatedFile build(boolean indirect) {
            return new RelatedFile(path, working, committed, observedAt, indirect);
        }
    }
}
