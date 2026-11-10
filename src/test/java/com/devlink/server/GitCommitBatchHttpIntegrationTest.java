package com.devlink.server;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** Calls the documented HTTP path through the real security chain and PostgreSQL-backed service. */
class GitCommitBatchHttpIntegrationTest extends AbstractIntegrationTest {
    private static final int LIMIT = 131_072;
    @LocalServerPort private int port;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private TeamMemberRepository members;
    @Autowired private AgentRepository agents;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwords;

    private Long teamId;
    private Long ownerId;
    private Long agentId;
    private String token;

    @BeforeEach
    void prepareCommittedAgentCredential() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            String unique = UUID.randomUUID().toString();
            User user = users.save(User.create(unique + "@batch-http.test", "HTTP 테스트 사용자", null, "test-hash"));
            Team team = teams.save(Team.create("HTTP 업로드 테스트", unique));
            teamId = team.getId();
            ownerId = user.getId();
            members.save(TeamMember.create(teamId, ownerId, MemberRole.OWNER));
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            Agent agent = Agent.create(teamId, ownerId, "HTTP Agent", null, ToolType.CODEX, "batch-http-test");
            agent.issueConnectionToken(AgentTokenHash.sha256(token));
            agentId = agents.saveAndFlush(agent).getId();
        });
    }

    @Test
    void documentedPostPathAuthenticatesAgentAndPersistsBatchWith200() throws Exception {
        var data = assertSuccess(post(validBody()));
        assertThat(data.path("repoId").asLong()).isPositive();
        assertThat(data.path("repoKey").asText()).isEqualTo("a".repeat(64));
        assertThat(data.path("receivedCount").asInt()).isEqualTo(1);
        assertThat(data.path("uniqueCount").asInt()).isEqualTo(1);
        assertThat(data.path("insertedCount").asInt()).isEqualTo(1);
        assertThat(data.path("updatedCount").asInt()).isZero();
        assertThat(data.path("duplicateCount").asInt()).isZero();
        assertThat(data.path("conflictCount").asInt()).isZero();
        assertThat(data.path("conflicts").size()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from repository_commits where team_id = ?",
                Long.class, teamId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from commit_files f
                join repository_commits c on c.id = f.commit_id where c.team_id = ?
                """, Long.class, teamId)).isEqualTo(1);
    }

    @Test
    void replayReturns200AndCountsDatabaseDuplicate() throws Exception {
        var first = assertSuccess(post(validBody()));

        var replay = assertSuccess(post(validBody()));

        assertThat(replay.path("repoId").asLong()).isEqualTo(first.path("repoId").asLong());
        assertThat(replay.path("insertedCount").asInt()).isZero();
        assertThat(replay.path("duplicateCount").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from repository_commits where team_id = ?",
                Long.class, teamId)).isEqualTo(1);
    }

    @Test
    void allowsExplicitNullAuthorAndEmptyFiles() throws Exception {
        ObjectNode body = validBody();
        ObjectNode commit = (ObjectNode) body.at("/commits/0");
        commit.putNull("authorUserId");
        commit.putArray("files");

        var data = assertSuccess(post(body));

        assertThat(data.path("insertedCount").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from repository_commits where team_id = ? and author_user_id is null",
                Long.class, teamId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from commit_files f
                join repository_commits c on c.id = f.commit_id where c.team_id = ?
                """, Long.class, teamId)).isZero();
    }

    @Test
    void logicalBatchConflictReturns200WithConflictDetails() throws Exception {
        ObjectNode body = validBody();
        ObjectNode changed = ((ObjectNode) body.at("/commits/0")).deepCopy();
        changed.put("committedAt", "2026-11-09T01:00:01Z");
        body.withArray("commits").add(changed);

        var data = assertSuccess(post(body));

        assertThat(data.path("receivedCount").asInt()).isEqualTo(2);
        assertThat(data.path("uniqueCount").asInt()).isEqualTo(1);
        assertThat(data.path("insertedCount").asInt()).isZero();
        assertThat(data.path("conflictCount").asInt()).isEqualTo(1);
        assertThat(data.at("/conflicts/0/reason").asText()).isEqualTo("BATCH_CONTENT_MISMATCH");
        assertThat(jdbc.queryForObject("select count(*) from repository_commits where team_id = ?",
                Long.class, teamId)).isZero();
    }

    @Test
    void missingAgentTokenReturns401BeforeWriting() throws Exception {
        assertError(send("POST", endpoint(teamId), null, publisher(validBody()), "application/json"),
                401, "AUTHENTICATION_REQUIRED");
        assertNoGitWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Basic invalid", "Bearer invalid-token", "Bearer invalid token", "invalid"})
    void invalidAuthorizationReturns401BeforeWriting(String authorization) throws Exception {
        assertError(send("POST", endpoint(teamId), authorization, publisher(validBody()), "application/json"),
                401, "AUTHENTICATION_REQUIRED");
        assertNoGitWrites();
    }

    @Test
    void currentTokenIsCheckedAfterRotation() throws Exception {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String replacement = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                agents.findById(agentId).orElseThrow().issueConnectionToken(AgentTokenHash.sha256(replacement)));

        assertError(post(validBody()), 401, "AUTHENTICATION_REQUIRED");
        assertNoGitWrites();
        assertSuccess(send("POST", endpoint(teamId), "Bearer " + replacement,
                publisher(validBody()), "application/json"));
    }

    @Test
    void removedMembershipInvalidatesAgentCredential() throws Exception {
        jdbc.update("delete from team_members where team_id = ? and user_id = ?", teamId, ownerId);

        assertError(post(validBody()), 401, "AUTHENTICATION_REQUIRED");
        assertNoGitWrites();
    }

    @Test
    void tokenCannotAccessAnotherTeamPath() throws Exception {
        assertError(send("POST", endpoint(teamId + 1_000_000), "Bearer " + token,
                publisher(validBody()), "application/json"), 403, "ACCESS_DENIED");
        assertNoGitWrites();
    }

    @Test
    void bodyCannotAttributeCommitsToAnotherUser() throws Exception {
        User other = users.save(User.create(UUID.randomUUID() + "@foreign.test",
                "다른 사용자", null, "test-hash"));
        ObjectNode body = validBody();
        ((ObjectNode) body.at("/commits/0")).put("authorUserId", other.getId());

        assertError(post(body), 403, "ACCESS_DENIED");
        assertNoGitWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void invalidTeamPathReturns400(String team) throws Exception {
        assertError(send("POST", "/api/v1/teams/" + team + "/git/commits:batch",
                "Bearer " + token, publisher(validBody()), "application/json"),
                400, "VALIDATION_FAILED");
        assertNoGitWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "DELETE", "PATCH"})
    void authenticatedWrongMethodReturns405(String method) throws Exception {
        assertError(send(method, endpoint(teamId), "Bearer " + token,
                HttpRequest.BodyPublishers.noBody(), null), 405, "METHOD_NOT_ALLOWED");
        assertNoGitWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "application/x-www-form-urlencoded"})
    void unsupportedContentTypeReturns415(String contentType) throws Exception {
        assertError(send("POST", endpoint(teamId), "Bearer " + token, publisher(validBody()), contentType),
                415, "UNSUPPORTED_MEDIA_TYPE");
        assertNoGitWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "[]", ""})
    void malformedBodyReturns400(String body) throws Exception {
        assertError(send("POST", endpoint(teamId), "Bearer " + token,
                HttpRequest.BodyPublishers.ofString(body), "application/json"), 400, "VALIDATION_FAILED");
        assertNoGitWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"authorString", "authorDecimal", "authorMissing", "authorOverflow",
            "changeTypeNumber", "changeTypeNumericString", "missingFiles", "missingCommits", "emptyCommits"})
    void invalidJsonFieldsReturn400WithoutPartialWrites(String invalidCase) throws Exception {
        ObjectNode body = validBody();
        ObjectNode commit = (ObjectNode) body.at("/commits/0");
        ObjectNode file = (ObjectNode) body.at("/commits/0/files/0");
        switch (invalidCase) {
            case "authorString" -> commit.put("authorUserId", ownerId.toString());
            case "authorDecimal" -> commit.put("authorUserId", ownerId.doubleValue());
            case "authorMissing" -> commit.remove("authorUserId");
            case "authorOverflow" -> commit.put("authorUserId", new BigInteger("9223372036854775808"));
            case "changeTypeNumber" -> file.put("changeType", 0);
            case "changeTypeNumericString" -> file.put("changeType", "0");
            case "missingFiles" -> commit.remove("files");
            case "missingCommits" -> body.remove("commits");
            case "emptyCommits" -> body.putArray("commits");
            default -> throw new IllegalArgumentException(invalidCase);
        }

        assertError(post(body), 400, "VALIDATION_FAILED");
        assertNoGitWrites();
    }

    @ParameterizedTest
    @CsvSource({"request, originUrl", "commit, authorEmail", "file, diff"})
    void unknownFieldsReturn400AtEveryLevel(String level, String field) throws Exception {
        ObjectNode body = validBody();
        ObjectNode target = switch (level) {
            case "request" -> body;
            case "commit" -> (ObjectNode) body.at("/commits/0");
            case "file" -> (ObjectNode) body.at("/commits/0/files/0");
            default -> throw new IllegalArgumentException(level);
        };
        target.put(field, "unexpected");

        assertError(post(body), 400, "VALIDATION_FAILED");
        assertNoGitWrites();
    }

    @Test
    void moreThan100RawCommitsReturn413BeforeDeduplication() throws Exception {
        ObjectNode body = validBody();
        ObjectNode commit = ((ObjectNode) body.at("/commits/0")).deepCopy();
        var array = body.putArray("commits");
        for (int index = 0; index < 101; index++) {
            array.add(commit.deepCopy());
        }
        assertThat(json.writeValueAsBytes(body).length).isLessThan(LIMIT);

        assertError(post(body), 413, "PAYLOAD_TOO_LARGE");
        assertNoGitWrites();
    }

    @ParameterizedTest
    @ValueSource(ints = {131_072, 131_073})
    void checksActualUtf8BodySize(int size) throws Exception {
        byte[] body = paddedBody(size);
        assertThat(new String(body, StandardCharsets.UTF_8).length()).isLessThan(LIMIT);

        var response = send("POST", endpoint(teamId), "Bearer " + token,
                HttpRequest.BodyPublishers.ofByteArray(body), "application/json");

        if (size <= LIMIT) {
            assertThat(assertSuccess(response).path("insertedCount").asInt()).isEqualTo(1);
        } else {
            assertError(response, 413, "PAYLOAD_TOO_LARGE");
            assertNoGitWrites();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {131_072, 131_073})
    void checksChunkedRequestsWithoutContentLength(int size) throws Exception {
        byte[] bytes = paddedBody(size);
        var publisher = HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes));
        assertThat(publisher.contentLength()).isEqualTo(-1);

        var response = send("POST", endpoint(teamId), "Bearer " + token, publisher, "application/json");

        if (size <= LIMIT) {
            assertSuccess(response);
        } else {
            assertError(response, 413, "PAYLOAD_TOO_LARGE");
            assertNoGitWrites();
        }
    }

    @Test
    void userSessionCannotReplaceAgentTokenAndRemainsValidAfterAgentCalls() throws Exception {
        String email = UUID.randomUUID() + "@session-http.test";
        String password = "Batch-http-test-123!";
        User browserUser = users.save(User.create(email, "웹 로그인 사용자", null, passwords.encode(password)));
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (HttpClient browser = HttpClient.newBuilder().cookieHandler(cookies)
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()) {
            var csrf = assertSuccess(browser.send(
                    requestBuilder("/api/v1/auth/csrf", null, null).GET().build(),
                    HttpResponse.BodyHandlers.ofString()));
            ObjectNode credentials = json.createObjectNode().put("email", email).put("password", password);
            assertSuccess(browser.send(requestBuilder("/api/v1/auth/login", null, "application/json")
                    .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                    .POST(publisher(credentials)).build(), HttpResponse.BodyHandlers.ofString()));
            csrf = assertSuccess(browser.send(
                    requestBuilder("/api/v1/auth/csrf", null, null).GET().build(),
                    HttpResponse.BodyHandlers.ofString()));

            assertError(browser.send(requestBuilder(endpoint(teamId), null, "application/json")
                    .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                    .POST(publisher(validBody())).build(), HttpResponse.BodyHandlers.ofString()),
                    401, "AUTHENTICATION_REQUIRED");
            assertError(browser.send(requestBuilder(endpoint(teamId), "Bearer invalid-token", "application/json")
                    .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                    .POST(publisher(validBody())).build(), HttpResponse.BodyHandlers.ofString()),
                    401, "AUTHENTICATION_REQUIRED");
            assertNoGitWrites();

            assertSuccess(browser.send(requestBuilder(endpoint(teamId), "Bearer " + token, "application/json")
                    .POST(publisher(validBody())).build(), HttpResponse.BodyHandlers.ofString()));
            assertThat(jdbc.queryForObject(
                    "select author_user_id from repository_commits where team_id = ?",
                    Long.class, teamId)).isEqualTo(ownerId);
            var currentUser = assertSuccess(browser.send(
                    requestBuilder("/api/v1/auth/users/me", null, null).GET().build(),
                    HttpResponse.BodyHandlers.ofString()));
            assertThat(currentUser.path("userId").asLong()).isEqualTo(browserUser.getId());
        }
    }

    private ObjectNode validBody() {
        ObjectNode body = json.createObjectNode().put("repoKey", "a".repeat(64));
        var commit = body.putArray("commits").addObject()
                .put("commitSha", "1".repeat(40))
                .put("committedAt", "2026-11-09T01:00:00Z")
                .put("authorUserId", ownerId);
        commit.putArray("files").addObject()
                .put("relativePath", "src/Auth.java")
                .put("changeType", "MODIFIED");
        return body;
    }

    private byte[] paddedBody(int size) throws Exception {
        ObjectNode body = validBody();
        ((ObjectNode) body.at("/commits/0/files/0")).put("relativePath", "src/" + "한".repeat(100) + ".java");
        String raw = json.writeValueAsString(body);
        int padding = size - raw.getBytes(StandardCharsets.UTF_8).length;
        assertThat(padding).isGreaterThanOrEqualTo(0);
        byte[] bytes = (raw + " ".repeat(padding)).getBytes(StandardCharsets.UTF_8);
        assertThat(bytes).hasSize(size);
        return bytes;
    }

    private HttpRequest.BodyPublisher publisher(ObjectNode body) throws Exception {
        return HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body));
    }

    private HttpResponse<String> post(ObjectNode body) throws Exception {
        return send("POST", endpoint(teamId), "Bearer " + token, publisher(body), "application/json");
    }

    private HttpResponse<String> send(String method, String path, String authorization,
            HttpRequest.BodyPublisher body, String contentType) throws Exception {
        var request = requestBuilder(path, authorization, contentType).method(method, body).build();
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private HttpRequest.Builder requestBuilder(String path, String authorization, String contentType) {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Accept", "application/json");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        return request;
    }

    private JsonNode assertSuccess(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as("Response: %s", response.body()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("code").isNull()).isTrue();
        assertThat(body.path("message").isNull()).isTrue();
        return body.path("data");
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as("Response: %s", response.body()).isEqualTo(status);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("data").isNull()).isTrue();
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("message").asText()).isNotBlank();
    }

    private void assertNoGitWrites() {
        assertThat(jdbc.queryForObject("select count(*) from team_repositories where team_id = ?",
                Long.class, teamId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from repository_commits where team_id = ?",
                Long.class, teamId)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from commit_files f
                join repository_commits c on c.id = f.commit_id where c.team_id = ?
                """, Long.class, teamId)).isZero();
    }

    private static String endpoint(Long teamId) {
        return "/api/v1/teams/" + teamId + "/git/commits:batch";
    }
}
