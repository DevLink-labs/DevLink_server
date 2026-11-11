package com.devlink.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.devlink.server.common.AgentTokenHash;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.teammember.TeamMember;
import com.devlink.server.domain.user.User;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TeamMemberRepository;
import com.devlink.server.repository.TeamRepository;
import com.devlink.server.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The production strict-mode property must protect both PUT aliases. */
@TestPropertySource(properties = "devlink.working-files.require-if-match=true")
class WorkingFileRevisionRequiredIntegrationTest extends AbstractIntegrationTest {
    private static final String KEY = "a".repeat(64);
    private static final String BODY = """
            {"branch":"main","files":[{"relativePath":"src/App.java","changeType":"MODIFIED"}]}
            """;
    @LocalServerPort private int port;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private TeamMemberRepository members;
    @Autowired private AgentRepository agents;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    private Long teamId;
    private Long userId;
    private String token;

    @BeforeEach
    void prepareCredential() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            String unique = UUID.randomUUID().toString();
            User user = users.save(User.create(unique + "@revision-required.test", "필수 헤더 검증", null, "test-hash"));
            Team team = teams.save(Team.create("revision 필수 모드", unique));
            userId = user.getId();
            teamId = team.getId();
            members.save(TeamMember.create(teamId, userId, MemberRole.OWNER));
            token = UUID.randomUUID() + "-" + UUID.randomUUID();
            Agent agent = Agent.create(teamId, userId, "필수 모드 Agent", null, ToolType.CODEX, "revision-required");
            agent.issueConnectionToken(AgentTokenHash.sha256(token));
            agents.saveAndFlush(agent);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingIfMatchReturns428WithoutPreparingRepositoryRevisionOrObservation(boolean alias) throws Exception {
        error(send("PUT", alias, null), 428, "WORKING_FILES_PRECONDITION_REQUIRED");
        assertThat(jdbc.queryForObject("select count(*) from team_repositories where team_id = ?", Long.class, teamId))
                .isZero();
        for (String table : new String[] {"working_file_revisions", "working_files"}) {
            assertThat(jdbc.queryForObject("select count(*) from " + table
                    + " w join team_repositories r on r.id = w.repo_id where r.team_id = ?", Long.class, teamId)).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void strictModeAllowsMatchingHeaderAndPreservesStateOn428And412(boolean alias) throws Exception {
        UUID initial = revision(send("GET", false, null));
        UUID current = revision(send("PUT", alias, initial));
        assertThat(current).isNotEqualTo(initial);
        Map<String, Object> before = snapshot();
        error(send("PUT", alias, null), 428, "WORKING_FILES_PRECONDITION_REQUIRED");
        assertThat(snapshot()).isEqualTo(before);
        error(send("PUT", alias, initial), 412, "WORKING_FILES_REVISION_MISMATCH");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(revision(send("GET", false, null))).isEqualTo(current);
        assertThat(revision(send("PUT", alias, current))).isNotEqualTo(current);
    }

    private HttpResponse<String> send(String method, boolean alias, UUID expected) throws Exception {
        String path = "/api/v1/teams/" + teamId + "/git/"
                + (alias ? "dependency-graph/" : "") + "working-files/" + KEY;
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json")
                .header("Authorization", "Bearer " + token);
        if (expected != null) builder.header("If-Match", "\"" + expected + "\"");
        if (method.equals("PUT")) builder.header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(BODY));
        else builder.GET();
        try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private UUID revision(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as("Response: %s", response.body()).isEqualTo(200);
        JsonNode envelope = json.readTree(response.body());
        assertThat(envelope.path("success").asBoolean()).isTrue();
        assertThat(envelope.path("data").path("protocolVersion").asInt()).isEqualTo(2);
        return UUID.fromString(envelope.path("data").path("revision").asText());
    }

    private void error(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as("Response: %s", response.body()).isEqualTo(status);
        JsonNode envelope = json.readTree(response.body());
        assertThat(envelope.path("success").asBoolean()).isFalse();
        assertThat(envelope.path("code").asText()).isEqualTo(code);
    }

    private Map<String, Object> snapshot() {
        return jdbc.queryForMap("""
                select w.*, v.revision from working_files w
                join working_file_revisions v on v.repo_id = w.repo_id and v.user_id = w.user_id
                join team_repositories r on r.id = w.repo_id
                where r.team_id = ? and r.origin_url_sha256 = ? and w.user_id = ?
                """, teamId, KEY, userId);
    }
}
