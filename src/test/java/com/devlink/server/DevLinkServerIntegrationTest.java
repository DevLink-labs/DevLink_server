package com.devlink.server;

import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

class DevLinkServerIntegrationTest extends AbstractIntegrationTest {
    @Autowired private TestRestTemplate rest;
    @Autowired private Flyway flyway;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @Test
    void brokerConnectionFailsClosedWithoutTokenVerificationImplementation() {
        var headers = new HttpHeaders();
        headers.setBearerAuth("unverified-agent-token");
        var response = rest.exchange("/ws/broker", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().path("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    void migratedSchemaPassesValidationAndHealthAndSecurityWork() {
        assertThat(entityManagerFactory.getMetamodel().getEntities())
                .extracting(entity -> entity.getName())
                .containsExactlyInAnyOrder("TaskFile", "TeamMember", "TaskEvent", "User",
                        "Team", "OutboxEvent", "Agent", "Task", "UserGitSetting",
                        "TeamGitRepository", "RepositoryCommit", "CommitFile", "WorkingFile",
                        "FileDependency");
        assertThat(flyway.info().applied())
                .extracting(migration -> migration.getVersion().getVersion())
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14");
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("14");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        List<String> tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class);
        assertThat(tables).containsExactlyInAnyOrder("users", "teams", "team_members", "agents",
                "tasks", "task_files", "task_events", "outbox_events", "flyway_schema_history",
                "user_git_settings", "team_repositories", "repository_commits", "commit_files",
                "working_files", "file_dependencies", "repository_dependency_graphs");

        var health = rest.getForEntity("/api/v1/health", JsonNode.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody().path("success").asBoolean()).isTrue();
        assertThat(health.getBody().path("data").asText()).isEqualTo("UP");

        var teams = rest.getForEntity("/api/v1/teams", JsonNode.class);
        assertThat(teams.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(teams.getHeaders().getLocation()).isNull();
        assertThat(teams.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(teams.getBody().path("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");

        var csrf = rest.postForEntity("/api/v1/auth/login", null, JsonNode.class);
        assertThat(csrf.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(csrf.getBody().path("code").asText()).isEqualTo("ACCESS_DENIED");
    }
}
