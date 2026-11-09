package com.devlink.server.dto.git;

import com.devlink.server.domain.gitrepo.WorkingFileChangeType;
import com.devlink.server.domain.gitrepo.WorkingFileEntry;
import java.time.Instant;
import java.util.List;

public final class DependencyGraphData {
    private DependencyGraphData() {}

    public enum RepoKind { FRONTEND, BACKEND }
    public enum NodeKind { PAGE, COMPONENT, HOOK, HTTP_CLIENT, ENDPOINT, SERVICE, REPOSITORY, DB_TABLE, MODULE }
    public enum Relation { IMPORT, RENDERS, CALLS, USES_TABLE, HTTP_CALL }

    public record RegisterSource(String repoKey, String displayName, String branchName, RepoKind repoKind,
            Long publisherAgentId, Long targetRepoId, boolean resetSnapshot) {}

    public record SourceConfig(String branchName, RepoKind repoKind, boolean publisher, long revision,
            String analysisCommitSha, Integer analysisVersion) {}

    public record Location(String relativePath, int line) {}
    public record HttpRoute(String method, String pathTemplate) {}
    public record Node(String id, NodeKind kind, String label, Location source, HttpRoute httpRoute,
            String schemaName) {}
    public record Edge(String sourceId, String targetId, Relation relationType) {}
    public record Snapshot(List<Node> nodes, List<Edge> edges) {}
    public record Upload(String repoKey, String branchName, long expectedRevision, String analysisCommitSha,
            int analysisVersion, List<Node> nodes, List<Edge> edges) {}

    public record RepositoryInfo(long repoId, String displayName, String branchName, RepoKind repoKind,
            String analysisCommitSha, long revision, Instant observedAt, Long publisherAgentId,
            Long targetRepoId) {}

    public record TeamNode(String id, long repoId, NodeKind kind, String label, Location source,
            HttpRoute httpRoute, String schemaName) {}

    public record TeamGraph(List<RepositoryInfo> repositories, List<TeamNode> nodes, List<Edge> edges,
            long unmatchedCallCount) {}

    /** 3차 작업 파일 계약과 같은 본문. branch는 detached HEAD면 null이다. */
    public record WorkingObservation(String branch, List<WorkingFileEntry> files) {}
    public record ActivityItem(long repoId, String relativePath, long userId, String displayName,
            String branchName, WorkingFileChangeType changeType, Instant observedAt, Instant expiresAt) {}
    public record Activity(Instant serverTime, List<ActivityItem> items) {}
}
