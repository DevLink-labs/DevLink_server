package com.devlink.server.repository;

import com.devlink.server.domain.gitrepo.WorkingFileEntry;
import com.devlink.server.dto.git.DependencyGraphData.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DependencyGraphRepository {
    public record Stored(RepositoryInfo info, Integer analysisVersion, Snapshot snapshot) {}
    public record FileEdge(String source, String target, String relation) {}

    private static final String SELECT = """
            select g.repo_id, g.display_name, g.branch_name, g.repo_kind, g.publisher_agent_id, g.target_repo_id,
                   g.revision, g.analysis_commit_sha, g.analysis_version, g.analyzed_at, g.graph
            from repository_dependency_graphs g join team_repositories r on r.id = g.repo_id
            where r.team_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public DependencyGraphRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public long lockRepository(long teamId, String repoKey) {
        ensureRepository(teamId, repoKey);
        return Objects.requireNonNull(jdbc.queryForObject("""
                select id from team_repositories where team_id = ? and origin_url_sha256 = ? for update
                """, Long.class, teamId, repoKey));
    }

    /** 팀 저장소 행이 없으면 만들고 id를 돌려준다. 작업 파일 관측은 그래프 등록 없이도 저장소를 만들 수 있다. */
    public long ensureRepository(long teamId, String repoKey) {
        jdbc.update("""
                insert into team_repositories (team_id, origin_url_sha256) values (?, ?)
                on conflict (team_id, origin_url_sha256) do nothing
                """, teamId, repoKey);
        return Objects.requireNonNull(jdbc.queryForObject("""
                select id from team_repositories where team_id = ? and origin_url_sha256 = ?
                """, Long.class, teamId, repoKey));
    }

    public List<Stored> list(long teamId) {
        return jdbc.query(SELECT + " order by g.repo_id", this::read, teamId);
    }

    public List<RepositoryInfo> listInfo(long teamId) {
        return jdbc.query(SELECT.replace(", g.graph", "") + " order by g.repo_id",
                (rs, index) -> info(rs), teamId);
    }

    public Optional<Stored> find(long teamId, String repoKey, boolean lock) {
        return jdbc.query(SELECT + " and r.origin_url_sha256 = ?" + (lock ? " for update of g" : ""),
                this::read, teamId, repoKey).stream().findFirst();
    }

    public Optional<RepositoryInfo> findInfo(long teamId, String repoKey) {
        return jdbc.query(SELECT.replace(", g.graph", "") + " and r.origin_url_sha256 = ?",
                (rs, index) -> info(rs), teamId, repoKey).stream().findFirst();
    }

    public void register(long repoId, RegisterSource request, boolean clear) {
        jdbc.update("""
                insert into repository_dependency_graphs
                    (repo_id, display_name, branch_name, repo_kind, publisher_agent_id, target_repo_id)
                values (?, ?, ?, ?, ?, ?)
                on conflict (repo_id) do update set
                    display_name = excluded.display_name, branch_name = excluded.branch_name,
                    repo_kind = excluded.repo_kind, publisher_agent_id = excluded.publisher_agent_id,
                    target_repo_id = excluded.target_repo_id, revision = repository_dependency_graphs.revision + 1
                """, repoId, request.displayName(), request.branchName(), request.repoKind().name(),
                request.publisherAgentId(), request.targetRepoId());
        if (clear) {
            jdbc.update("""
                    update repository_dependency_graphs
                    set analysis_commit_sha = null, analysis_version = null, analyzed_at = null, graph = null
                    where repo_id = ?
                    """, repoId);
            clearFileEdges(repoId);
        }
    }

    public void publish(long repoId, String sha, int version, Instant now, Snapshot snapshot, List<FileEdge> edges) {
        clearFileEdges(repoId);
        jdbc.batchUpdate("""
                insert into file_dependencies
                    (repo_id, analysis_commit_sha, source_relative_path, target_relative_path, relation_type, observed_at)
                values (?, ?, ?, ?, ?, ?)
                """, edges, 250, (statement, edge) -> {
            statement.setLong(1, repoId);
            statement.setString(2, sha);
            statement.setString(3, edge.source());
            statement.setString(4, edge.target());
            statement.setString(5, edge.relation());
            statement.setTimestamp(6, Timestamp.from(now));
        });
        jdbc.update("""
                update repository_dependency_graphs set revision = revision + 1, analysis_commit_sha = ?,
                    analysis_version = ?, analyzed_at = ?, graph = cast(? as jsonb)
                where repo_id = ?
                """, sha, version, Timestamp.from(now), encode(snapshot), repoId);
    }

    public void observe(long repoId, long userId, String branch, List<WorkingFileEntry> files, Instant now) {
        jdbc.update("""
                insert into working_files (repo_id, user_id, branch_name, files, observed_at, expires_at)
                values (?, ?, ?, cast(? as jsonb), ?, ?)
                on conflict (repo_id, user_id) do update set branch_name = excluded.branch_name,
                    files = excluded.files, observed_at = excluded.observed_at, expires_at = excluded.expires_at
                where working_files.observed_at <= excluded.observed_at
                """, repoId, userId, branch, encode(files), Timestamp.from(now), Timestamp.from(now.plusSeconds(90)));
    }

    private void clearFileEdges(long repoId) {
        jdbc.update("delete from file_dependencies where repo_id = ?", repoId);
    }

    private Stored read(ResultSet rs, int index) throws SQLException {
        String graph = rs.getString("graph");
        try {
            return new Stored(info(rs), rs.getObject("analysis_version", Integer.class),
                    graph == null ? null : json.readValue(graph, Snapshot.class));
        } catch (JsonProcessingException exception) {
            throw new SQLException("Invalid stored dependency graph", exception);
        }
    }

    private RepositoryInfo info(ResultSet rs) throws SQLException {
        Timestamp timestamp = rs.getTimestamp("analyzed_at");
        return new RepositoryInfo(rs.getLong("repo_id"), rs.getString("display_name"), rs.getString("branch_name"),
                RepoKind.valueOf(rs.getString("repo_kind")), rs.getString("analysis_commit_sha"),
                rs.getLong("revision"), timestamp == null ? null : timestamp.toInstant(),
                rs.getObject("publisher_agent_id", Long.class), rs.getObject("target_repo_id", Long.class));
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize dependency graph data", exception);
        }
    }
}
