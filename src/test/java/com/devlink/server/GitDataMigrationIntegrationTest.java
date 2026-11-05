package com.devlink.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class GitDataMigrationIntegrationTest extends AbstractIntegrationTest {
    @Autowired private DataSource dataSource;

    private Connection connection;
    private JdbcTemplate db;
    private String schema;
    private long userId;
    private long otherUserId;
    private long teamId;
    private long otherTeamId;
    private long agentId;
    private long taskId;
    private Map<String, Object> previousAgent;
    private Map<String, Object> previousTask;

    @BeforeEach
    void migrateExistingV9Data() throws SQLException {
        // Testcontainers DB 안의 독립 스키마에서 기존 데이터가 있는 V9 → V10을 검증한다.
        schema = "git_migration_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .target("9").load().migrate();
        connection = dataSource.getConnection();
        connection.setSchema(schema);
        db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        userId = db.queryForObject("""
                insert into users (email, name, password) values ('owner@example.com', '기존 사용자', 'test') returning id
                """, Long.class);
        otherUserId = db.queryForObject("""
                insert into users (email, name, password) values ('other@example.com', '다른 사용자', 'test') returning id
                """, Long.class);
        teamId = db.queryForObject("insert into teams (name, invite_code) values ('기존 팀', 'first') returning id", Long.class);
        otherTeamId = db.queryForObject("insert into teams (name, invite_code) values ('다른 팀', 'second') returning id", Long.class);
        agentId = db.queryForObject("""
                insert into agents (team_id, owner_user_id, name, role_description, tool_type, project_name)
                values (?, ?, '기존 Agent', '기존 역할 설명', 'CODEX', 'DevLink') returning id
                """, Long.class, teamId, userId);
        taskId = db.queryForObject("""
                insert into tasks (request_key, team_id, requester_agent_id, requester_user_id, receiver_agent_id,
                    prompt, task_summary) values ('legacy-task', ?, ?, ?, ?, '기존 요청', '기존 요약') returning id
                """, Long.class, teamId, agentId, userId, agentId);
        previousAgent = db.queryForMap("select * from agents where id = ?", agentId);
        previousTask = db.queryForMap("select * from tasks where id = ?", taskId);

        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
    }

    @AfterEach
    void releaseConnection() throws SQLException {
        if (connection != null) {
            try {
                connection.setSchema("public");
            } finally {
                connection.close();
            }
        }
    }

    @Test
    void upgradePreservesExistingDataAndAddsDefaultsAndTimezoneAwareColumns() {
        Map<String, Object> agent = db.queryForMap("select * from agents where id = ?", agentId);
        previousAgent.forEach((key, value) -> assertThat(agent.get(key)).as(key).isEqualTo(value));
        assertThat(agent.get("part")).isEqualTo("OTHER");
        assertThat(db.queryForObject("select roles::text from agents where id = ?", String.class, agentId)).isEqualTo("[]");
        assertThat(db.queryForMap("select * from tasks where id = ?", taskId)).isEqualTo(previousTask);
        for (String table : List.of("user_git_settings", "team_repositories", "repository_commits", "commit_files",
                "working_files", "file_dependencies")) {
            assertThat(db.queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();
        }
        assertThat(db.queryForList("""
                select data_type from information_schema.columns
                where table_schema = ? and table_name in ('user_git_settings', 'team_repositories',
                    'repository_commits', 'working_files', 'file_dependencies')
                  and column_name in ('registered_at', 'created_at', 'committed_at', 'first_seen_at', 'observed_at', 'expires_at')
                """, String.class, schema))
                .hasSize(7).containsOnly("timestamp with time zone");
        assertThatThrownBy(() -> db.update("update agents set part = 'UNKNOWN' where id = ?", agentId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("update agents set roles = '{}'::jsonb where id = ?", agentId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void gitEmailStoresPlaintextAndEnforcesOneIdentityPerUserAndUniqueEmail() {
        db.update("insert into user_git_settings (user_id, git_email) values (?, ?)", userId, "git-owner@example.com");
        assertThat(db.queryForObject("select git_email from user_git_settings where user_id = ?", String.class, userId))
                .isEqualTo("git-owner@example.com");
        assertThat(db.queryForMap("select registered_at from user_git_settings where user_id = ?", userId)
                .get("registered_at")).isNotNull();
        assertThatThrownBy(() -> db.update("insert into user_git_settings (user_id, git_email) values (?, ?)",
                userId, "replacement@example.com")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("insert into user_git_settings (user_id, git_email) values (?, ?)",
                otherUserId, "git-owner@example.com")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("insert into user_git_settings (user_id, git_email) values (?, ?)",
                Long.MAX_VALUE, "missing@example.com")).isInstanceOf(DataIntegrityViolationException.class);
        db.update("update user_git_settings set git_email = ? where user_id = ?", "replacement@example.com", userId);
        assertThat(db.queryForObject("select count(*) from user_git_settings", Long.class)).isEqualTo(1L);
    }

    @Test
    void repositoriesAndCommitsEnforceTeamScopeDeduplicationAndNullableAuthor() {
        long repoId = repository(teamId, "a".repeat(64));
        long otherRepoId = repository(otherTeamId, "a".repeat(64));
        assertThatThrownBy(() -> repository(teamId, "a".repeat(64))).isInstanceOf(DataIntegrityViolationException.class);
        long commitId = commit(teamId, repoId, "b".repeat(40), userId);
        assertThatThrownBy(() -> commit(teamId, repoId, "b".repeat(40), userId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> commit(otherTeamId, repoId, "c".repeat(40), userId))
                .isInstanceOf(DataIntegrityViolationException.class);
        commit(otherTeamId, otherRepoId, "b".repeat(40), null);
        long unknownAuthor = commit(teamId, repoId, "d".repeat(64), null);
        assertThat(db.queryForMap("select author_user_id, first_seen_at from repository_commits where id = ?", unknownAuthor))
                .containsEntry("author_user_id", null);
        assertThat(db.queryForObject("select committed_at at time zone 'UTC' from repository_commits where id = ?",
                LocalDateTime.class, commitId)).isEqualTo(LocalDateTime.of(2026, 11, 5, 0, 0));
        assertThatThrownBy(() -> commit(teamId, repoId, "e".repeat(40), Long.MAX_VALUE))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void commitFilesEnforceCommitPathIdentityAndSupportedChangeTypes() {
        long repoId = repository(teamId, "a".repeat(64));
        long first = commit(teamId, repoId, "b".repeat(40), userId);
        long second = commit(teamId, repoId, "c".repeat(40), null);
        db.update("insert into commit_files values (?, 'src/Example.java', 'MODIFIED')", first);
        db.update("insert into commit_files values (?, 'src/Example.java', 'RENAMED')", second);
        assertThatThrownBy(() -> db.update("insert into commit_files values (?, 'src/Example.java', 'ADDED')", first))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("insert into commit_files values (?, 'src/Invalid.java', 'UNKNOWN')", first))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("insert into commit_files values (?, 'src/Missing.java', 'ADDED')", Long.MAX_VALUE))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(db.queryForObject("select count(*) from commit_files where relative_path = 'src/Example.java'", Long.class))
                .isEqualTo(2L);
    }

    @Test
    void workingFilesKeepEmptyObservationsAndSeparateUsersAndRepositories() {
        long firstRepo = repository(teamId, "a".repeat(64));
        long secondRepo = repository(teamId, "b".repeat(64));
        observe(firstRepo, userId, "[]");
        observe(firstRepo, otherUserId, "[]");
        observe(secondRepo, userId, "[]");
        assertThat(db.queryForObject("select count(*) from working_files", Long.class)).isEqualTo(3L);
        assertThat(db.queryForObject("select files::text from working_files where repo_id = ? and user_id = ?",
                String.class, firstRepo, userId)).isEqualTo("[]");
        assertThatThrownBy(() -> observe(firstRepo, userId, "[]")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("update working_files set files = '{}'::jsonb where repo_id = ?", firstRepo))
                .isInstanceOf(DataIntegrityViolationException.class);
        db.update("update working_files set files = ?::jsonb where repo_id = ? and user_id = ?",
                "[{\"relativePath\":\"src/Example.java\",\"changeType\":\"MODIFIED\"}]", firstRepo, userId);
        assertThat(db.queryForObject("select jsonb_array_length(files) from working_files where repo_id = ? and user_id = ?",
                Integer.class, firstRepo, userId)).isEqualTo(1);
        db.update("update working_files set files = '[]'::jsonb where repo_id = ? and user_id = ?", firstRepo, userId);
        assertThat(db.queryForObject("select count(*) from working_files", Long.class)).isEqualTo(3L);
        assertThatThrownBy(() -> observe(Long.MAX_VALUE, userId, "[]")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> observe(firstRepo, Long.MAX_VALUE, "[]")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reservedDependenciesSeparateAnalysisVersionsAndSupportReverseLookup() {
        long repoId = repository(teamId, "a".repeat(64));
        dependency(repoId, "b".repeat(40), "src/First.java", "src/Shared.java");
        dependency(repoId, "c".repeat(40), "src/First.java", "src/Shared.java");
        assertThatThrownBy(() -> dependency(repoId, "b".repeat(40), "src/First.java", "src/Shared.java"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> dependency(Long.MAX_VALUE, "b".repeat(40), "src/First.java", "src/Shared.java"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(db.queryForList("""
                select source_relative_path from file_dependencies
                where repo_id = ? and analysis_commit_sha = ? and target_relative_path = ? and relation_type = 'IMPORT'
                """, String.class, repoId, "b".repeat(40), "src/Shared.java")).containsExactly("src/First.java");
        assertThat(db.queryForObject("select count(*) from pg_indexes where schemaname = ? and indexname = ?",
                Long.class, schema, "ix_file_dependencies_reverse")).isEqualTo(1L);
    }

    private long repository(long team, String hash) {
        return db.queryForObject("insert into team_repositories (team_id, origin_url_sha256) values (?, ?) returning id",
                Long.class, team, hash);
    }

    private long commit(long team, long repo, String sha, Long author) {
        return db.queryForObject("""
                insert into repository_commits (team_id, repo_id, commit_sha, committed_at, author_user_id)
                values (?, ?, ?, TIMESTAMPTZ '2026-11-05 09:00:00+09:00', ?) returning id
                """, Long.class, team, repo, sha, author);
    }

    private void observe(long repo, long user, String files) {
        db.update("""
                insert into working_files (repo_id, user_id, branch_name, files, observed_at, expires_at)
                values (?, ?, 'main', ?::jsonb, TIMESTAMPTZ '2026-11-05 00:00:00+00', TIMESTAMPTZ '2026-11-05 00:01:30+00')
                """, repo, user, files);
    }

    private void dependency(long repo, String sha, String source, String target) {
        db.update("""
                insert into file_dependencies (repo_id, source_relative_path, target_relative_path,
                    relation_type, analysis_commit_sha, observed_at)
                values (?, ?, ?, 'IMPORT', ?, CURRENT_TIMESTAMP)
                """, repo, source, target, sha);
    }
}
