package com.devlink.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real HTTP and PostgreSQL checks for revision preparation without recording a file observation. */
class WorkingFileRevisionGetIntegrationTest extends AbstractIntegrationTest {
    private static final String KEY = "a".repeat(64);
    @LocalServerPort private int port;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private TeamMemberRepository members;
    @Autowired private AgentRepository agents;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    private Credential owner;
    private Credential sameOwner;
    private Credential teammate;
    private Credential otherTeam;

    private record Credential(Long teamId, Long userId, Long agentId, String token) {}

    @BeforeEach
    void prepareCommittedCredentials() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            String unique = UUID.randomUUID().toString();
            User first = users.save(User.create(unique + "@revision.test", "revision 사용자", null, "test-hash"));
            User second = users.save(User.create(unique + "@revision-other.test", "다른 사용자", null, "test-hash"));
            Team team = teams.save(Team.create("revision 검증", unique));
            Team another = teams.save(Team.create("다른 revision 팀", unique + "-other"));
            members.save(TeamMember.create(team.getId(), first.getId(), MemberRole.OWNER));
            members.save(TeamMember.create(team.getId(), second.getId(), MemberRole.MEMBER));
            members.save(TeamMember.create(another.getId(), first.getId(), MemberRole.OWNER));
            owner = credential(team.getId(), first.getId(), "첫 Agent");
            sameOwner = credential(team.getId(), first.getId(), "같은 사용자 Agent");
            teammate = credential(team.getId(), second.getId(), "다른 사용자 Agent");
            otherTeam = credential(another.getId(), first.getId(), "다른 팀 Agent");
        });
    }

    @Test
    void firstGetCreatesOnlyRepositoryAndRevisionWithNoStoreResponse() throws Exception {
        HttpResponse<String> response = get(owner, KEY);
        UUID revision = revision(response);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(storedRevision(owner, KEY)).isEqualTo(revision);
        assertThat(count("team_repositories", owner.teamId())).isEqualTo(1);
        assertThat(count("working_file_revisions", owner.teamId())).isEqualTo(1);
        assertThat(count("working_files", owner.teamId())).isZero();
    }

    @Test
    void repeatedGetKeepsTheSameRevisionWithoutCreatingAnObservation() throws Exception {
        UUID original = revision(get(owner, KEY));
        assertThat(revision(get(owner, KEY))).isEqualTo(original);
        assertThat(revision(get(owner, KEY))).isEqualTo(original);
        assertThat(count("working_file_revisions", owner.teamId())).isEqualTo(1);
        assertThat(count("working_files", owner.teamId())).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void getDoesNotChangeExistingFreshOrExpiredObservation(boolean expired) throws Exception {
        putObservation();
        if (expired) {
            jdbc.update("""
                    update working_files set observed_at = now() - interval '180 seconds',
                        expires_at = now() - interval '90 seconds'
                    where repo_id = ? and user_id = ?
                    """, repoId(owner, KEY), owner.userId());
        }
        Map<String, Object> before = observation();
        UUID original = revision(get(owner, KEY));
        assertThat(revision(get(owner, KEY))).isEqualTo(original);
        assertThat(observation()).isEqualTo(before);
    }

    @Test
    void observationDeletionDoesNotResetRevisionOrRecreateObservation() throws Exception {
        putObservation();
        UUID original = revision(get(owner, KEY));
        jdbc.update("delete from working_files where repo_id = ? and user_id = ?",
                repoId(owner, KEY), owner.userId());
        assertThat(revision(get(owner, KEY))).isEqualTo(original);
        assertThat(count("working_files", owner.teamId())).isZero();
    }

    @Test
    void recreatingADeletedRevisionGeneratesANewUuid() throws Exception {
        UUID original = revision(get(owner, KEY));
        jdbc.update("delete from working_file_revisions where repo_id = ? and user_id = ?",
                repoId(owner, KEY), owner.userId());
        assertThat(revision(get(owner, KEY))).isNotEqualTo(original);
        assertThat(count("working_files", owner.teamId())).isZero();
    }

    @Test
    void agentsWithTheSameOwnerShareRevisionButOtherUsersRepositoriesAndTeamsDoNot() throws Exception {
        UUID original = revision(get(owner, KEY));
        assertThat(revision(get(sameOwner, KEY))).isEqualTo(original);
        UUID differentUser = revision(get(teammate, KEY));
        UUID differentRepo = revision(get(owner, "b".repeat(64)));
        UUID differentTeam = revision(get(otherTeam, KEY));
        assertThat(List.of(original, differentUser, differentRepo, differentTeam)).doesNotHaveDuplicates();
        assertThat(count("working_file_revisions", owner.teamId())).isEqualTo(3);
        assertThat(count("working_file_revisions", otherTeam.teamId())).isEqualTo(1);
        assertThat(count("working_files", owner.teamId())).isZero();
    }

    @Test
    void concurrentFirstGetsReturnOneStableRevisionWithoutRecordingFiles() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            var pending = IntStream.range(0, 6).mapToObj(i -> pool.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return revision(get(owner, KEY));
            })).toList();
            start.countDown();
            UUID expected = null;
            for (var future : pending) {
                UUID actual = future.get(30, TimeUnit.SECONDS);
                if (expected == null) expected = actual;
                assertThat(actual).isEqualTo(expected);
            }
            assertThat(storedRevision(owner, KEY)).isEqualTo(expected);
        }
        assertThat(count("team_repositories", owner.teamId())).isEqualTo(1);
        assertThat(count("working_file_revisions", owner.teamId())).isEqualTo(1);
        assertThat(count("working_files", owner.teamId())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Basic invalid", "Bearer invalid-token"})
    void missingOrInvalidAgentAuthenticationCreatesNothing(String authorization) throws Exception {
        error(send("GET", path(owner.teamId(), KEY), authorization, null), 401, "AUTHENTICATION_REQUIRED");
        assertNoWrites();
    }

    @Test
    void otherTeamPathIsRejectedEvenWhenOwnerBelongsToBothTeams() throws Exception {
        error(send("GET", path(otherTeam.teamId(), KEY), "Bearer " + owner.token(), null),
                403, "ACCESS_DENIED");
        assertNoWrites();
        assertThat(count("team_repositories", otherTeam.teamId())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void invalidTeamIdCreatesNothing(String team) throws Exception {
        error(send("GET", "/api/v1/teams/" + team + "/git/working-files/" + KEY,
                "Bearer " + owner.token(), null), 400, "VALIDATION_FAILED");
        assertNoWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg"})
    void invalidRepoKeyCreatesNothing(String key) throws Exception {
        error(get(owner, key), 400, "VALIDATION_FAILED");
        assertNoWrites();
    }

    @Test
    void departedOwnerCannotPrepareRevision() throws Exception {
        jdbc.update("delete from team_members where team_id = ? and user_id = ?", owner.teamId(), owner.userId());
        error(get(owner, KEY), 401, "AUTHENTICATION_REQUIRED");
        assertNoWrites();
    }

    private Credential credential(Long team, Long user, String name) {
        String token = UUID.randomUUID() + "-" + UUID.randomUUID();
        Agent agent = Agent.create(team, user, name, null, ToolType.CODEX, "revision-get");
        agent.issueConnectionToken(AgentTokenHash.sha256(token));
        return new Credential(team, user, agents.saveAndFlush(agent).getId(), token);
    }

    private HttpResponse<String> get(Credential caller, String key) throws Exception {
        return send("GET", path(caller.teamId(), key), "Bearer " + caller.token(), null);
    }

    private void putObservation() throws Exception {
        HttpResponse<String> response = send("PUT", path(owner.teamId(), KEY), "Bearer " + owner.token(), """
                {"branch":"feature/revision","files":[{"relativePath":"src/App.java","changeType":"MODIFIED"}]}
                """);
        assertThat(response.statusCode()).as("Response: %s", response.body()).isEqualTo(200);
    }

    private String path(Long team, String key) {
        return "/api/v1/teams/" + team + "/git/working-files/" + key;
    }

    private HttpResponse<String> send(String method, String path, String authorization, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json");
        if (!authorization.isEmpty()) builder.header("Authorization", authorization);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private UUID revision(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as("Response: %s", response.body()).isEqualTo(200);
        JsonNode envelope = json.readTree(response.body());
        assertThat(envelope.path("success").asBoolean()).isTrue();
        assertThat(envelope.path("code").isNull()).isTrue();
        assertThat(envelope.path("message").isNull()).isTrue();
        assertThat(envelope.path("data").path("protocolVersion").asInt()).isEqualTo(2);
        return UUID.fromString(envelope.path("data").path("revision").asText());
    }

    private void error(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as("Response: %s", response.body()).isEqualTo(status);
        JsonNode envelope = json.readTree(response.body());
        assertThat(envelope.path("success").asBoolean()).isFalse();
        assertThat(envelope.path("data").isNull()).isTrue();
        assertThat(envelope.path("code").asText()).isEqualTo(code);
    }

    private long repoId(Credential caller, String key) {
        return jdbc.queryForObject("select id from team_repositories where team_id = ? and origin_url_sha256 = ?",
                Long.class, caller.teamId(), key);
    }

    private UUID storedRevision(Credential caller, String key) {
        return jdbc.queryForObject("select revision from working_file_revisions where repo_id = ? and user_id = ?",
                UUID.class, repoId(caller, key), caller.userId());
    }

    private Map<String, Object> observation() {
        return jdbc.queryForMap("select * from working_files where repo_id = ? and user_id = ?",
                repoId(owner, KEY), owner.userId());
    }

    private long count(String table, Long team) {
        String sql = table.equals("team_repositories")
                ? "select count(*) from team_repositories where team_id = ?"
                : "select count(*) from " + table + " w join team_repositories r on r.id = w.repo_id where r.team_id = ?";
        return jdbc.queryForObject(sql, Long.class, team);
    }

    private void assertNoWrites() {
        assertThat(count("team_repositories", owner.teamId())).isZero();
        assertThat(count("working_file_revisions", owner.teamId())).isZero();
        assertThat(count("working_files", owner.teamId())).isZero();
    }
}
