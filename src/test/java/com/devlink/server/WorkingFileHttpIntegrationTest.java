package com.devlink.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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

/** Real HTTP, Agent token verification, MVC request advice, service and PostgreSQL. */
class WorkingFileHttpIntegrationTest extends AbstractIntegrationTest {
    private static final String REPO_KEY = "a".repeat(64);
    private static final int LIMIT = 2_097_152;
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
    void prepareCommittedCredential() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            String unique = UUID.randomUUID().toString();
            User user = users.save(User.create(unique + "@working-http.test", "작업 파일 HTTP 사용자", null, "test-hash"));
            Team team = teams.save(Team.create("작업 파일 HTTP 검증", unique));
            teamId = team.getId();
            ownerId = user.getId();
            members.save(TeamMember.create(teamId, ownerId, MemberRole.OWNER));
            token = randomToken();
            Agent agent = Agent.create(teamId, ownerId, "HTTP Agent", null, ToolType.CODEX, "working-http");
            agent.issueConnectionToken(AgentTokenHash.sha256(token));
            agentId = agents.saveAndFlush(agent).getId();
        });
    }

    @Test
    void documentedPutPathReturns200AndPersistsOwnerAndNinetySecondExpiry() throws Exception {
        ObjectNode body = validBody();
        file(body).put("relativePath", "src/한글😀.tsx").put("changeType", "UNTRACKED");
        Instant before = Instant.now().minusSeconds(1);
        assertThat(success(put(body)).isNull()).isTrue();

        Map<String, Object> saved = stored();
        assertThat(saved.get("user_id")).isEqualTo(ownerId);
        assertThat(saved.get("branch_name")).isEqualTo("feature/login");
        JsonNode files = json.readTree(saved.get("files").toString());
        assertThat(files.size()).isEqualTo(1);
        assertThat(files.get(0).path("relativePath").asText()).isEqualTo("src/한글😀.tsx");
        assertThat(files.get(0).path("changeType").asText()).isEqualTo("UNTRACKED");
        Instant observed = ((Timestamp) saved.get("observed_at")).toInstant();
        Instant expires = ((Timestamp) saved.get("expires_at")).toInstant();
        assertThat(observed).isBetween(before, Instant.now().plusSeconds(1));
        assertThat(Duration.between(observed, expires)).isEqualTo(Duration.ofSeconds(90));
        assertThat(repositoryCount()).isEqualTo(1);
    }

    @Test
    void replacesWholeListAndAcceptsDetachedHeadEmptyObservation() throws Exception {
        ObjectNode body = validBody();
        body.withArray("files").addObject().put("relativePath", "src/Removed.tsx").put("changeType", "ADDED");
        success(put(body));
        ObjectNode replacement = validBody().put("branch", "feature/new");
        file(replacement).put("relativePath", "src/New.tsx");
        success(put(replacement));
        assertThat(json.readTree(stored().get("files").toString()).size()).isEqualTo(1);
        assertThat(json.readTree(stored().get("files").toString()).get(0).path("relativePath").asText())
                .isEqualTo("src/New.tsx");

        ObjectNode empty = json.createObjectNode().putNull("branch");
        empty.putArray("files");
        assertThat(success(put(empty)).isNull()).isTrue();
        assertThat(stored().get("branch_name")).isNull();
        assertThat(json.readTree(stored().get("files").toString()).isEmpty()).isTrue();
        assertThat(observationCount()).isEqualTo(1);
    }

    @Test
    void missingTokenReturns401() throws Exception {
        error(send("PUT", endpoint(teamId, REPO_KEY), null, publisher(validBody()), "application/json"),
                401, "AUTHENTICATION_REQUIRED");
        assertNoWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Basic invalid", "Bearer invalid-token", "Bearer invalid token", "invalid"})
    void invalidAuthorizationReturns401(String authorization) throws Exception {
        error(send("PUT", endpoint(teamId, REPO_KEY), authorization, publisher(validBody()), "application/json"),
                401, "AUTHENTICATION_REQUIRED");
        assertNoWrites();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void revokedOrRotatedTokenIsRejected(boolean rotated) throws Exception {
        String replacement = randomToken();
        if (rotated) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    agents.findById(agentId).orElseThrow().issueConnectionToken(AgentTokenHash.sha256(replacement)));
        } else {
            jdbc.update("update agents set connection_token_hash = null where id = ?", agentId);
        }
        error(put(validBody()), 401, "AUTHENTICATION_REQUIRED");
        assertNoWrites();
        if (rotated) {
            success(send("PUT", endpoint(teamId, REPO_KEY), "Bearer " + replacement,
                    publisher(validBody()), "application/json"));
        }
    }

    @Test
    void departedOwnerInvalidatesToken() throws Exception {
        jdbc.update("delete from team_members where team_id = ? and user_id = ?", teamId, ownerId);
        error(put(validBody()), 401, "AUTHENTICATION_REQUIRED");
        assertNoWrites();
    }

    @Test
    void anotherTeamPathReturns403() throws Exception {
        error(send("PUT", endpoint(teamId + 1_000_000, REPO_KEY), bearer(),
                publisher(validBody()), "application/json"), 403, "ACCESS_DENIED");
        assertNoWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void invalidTeamIdsReturn400(String team) throws Exception {
        error(send("PUT", "/api/v1/teams/" + team + "/git/working-files/" + REPO_KEY, bearer(),
                publisher(validBody()), "application/json"), 400, "VALIDATION_FAILED");
        assertNoWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg", "not-a-repo-key"})
    void invalidRepoKeyReturns400(String key) throws Exception {
        error(send("PUT", endpoint(teamId, key), bearer(), publisher(validBody()), "application/json"),
                400, "VALIDATION_FAILED");
        assertNoWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "DELETE", "PATCH"})
    void authenticatedUnsupportedMethodReturns405(String method) throws Exception {
        error(send(method, endpoint(teamId, REPO_KEY), bearer(), HttpRequest.BodyPublishers.noBody(), null),
                405, "METHOD_NOT_ALLOWED");
        assertNoWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "application/x-www-form-urlencoded"})
    void unsupportedContentTypeReturns415(String contentType) throws Exception {
        error(send("PUT", endpoint(teamId, REPO_KEY), bearer(), publisher(validBody()), contentType),
                415, "UNSUPPORTED_MEDIA_TYPE");
        assertNoWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "[]", ""})
    void malformedOrMissingBodyReturns400(String body) throws Exception {
        error(send("PUT", endpoint(teamId, REPO_KEY), bearer(), HttpRequest.BodyPublishers.ofString(body),
                "application/json"), 400, "VALIDATION_FAILED");
        assertNoWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"branchNumber", "blankBranch", "missingFiles", "nullFiles", "nullElement",
            "missingPath", "nullType", "numericType", "numericStringType", "renamedType", "parentPath", "duplicatePath"})
    void invalidFieldsReturn400WithoutPartialWrites(String invalidCase) throws Exception {
        ObjectNode body = validBody();
        switch (invalidCase) {
            case "branchNumber" -> body.put("branch", 123);
            case "blankBranch" -> body.put("branch", " ");
            case "missingFiles" -> body.remove("files");
            case "nullFiles" -> body.putNull("files");
            case "nullElement" -> body.withArray("files").addNull();
            case "missingPath" -> file(body).remove("relativePath");
            case "nullType" -> file(body).putNull("changeType");
            case "numericType" -> file(body).put("changeType", 0);
            case "numericStringType" -> file(body).put("changeType", "0");
            case "renamedType" -> file(body).put("changeType", "RENAMED");
            case "parentPath" -> file(body).put("relativePath", "src/../Bad.tsx");
            case "duplicatePath" -> body.withArray("files").add(file(body).deepCopy());
            default -> throw new IllegalArgumentException(invalidCase);
        }
        error(put(body), 400, "VALIDATION_FAILED");
        assertNoWrites();
    }

    @ParameterizedTest
    @CsvSource({"request,userId", "request,repoKey", "request,observedAt", "file,content", "file,diff"})
    void rejectsUnknownAndIdentityOverrideFields(String level, String field) throws Exception {
        ObjectNode body = validBody();
        (level.equals("request") ? body : file(body)).put(field, "unexpected");
        error(put(body), 400, "VALIDATION_FAILED");
        assertNoWrites();
    }

    @ParameterizedTest
    @ValueSource(ints = {2000, 2001})
    void enforcesFileCountAtTheRealHttpEndpoint(int count) throws Exception {
        ObjectNode body = validBody();
        var files = body.putArray("files");
        for (int i = 0; i < count; i++) {
            files.addObject().put("relativePath", "src/F" + i + ".tsx").put("changeType", "MODIFIED");
        }
        if (count == 2000) {
            success(put(body));
            assertThat(json.readTree(stored().get("files").toString()).size()).isEqualTo(2000);
        } else {
            error(put(body), 400, "VALIDATION_FAILED");
            assertNoWrites();
        }
    }

    @ParameterizedTest
    @CsvSource({"2097152,false", "2097153,false", "2097152,true", "2097153,true"})
    void enforcesUtf8TwoMiBLimitForKnownLengthAndChunkedRequests(int size, boolean chunked) throws Exception {
        byte[] bytes = paddedBody(size);
        assertThat(new String(bytes, StandardCharsets.UTF_8).length()).isLessThan(LIMIT);
        HttpRequest.BodyPublisher publisher = chunked
                ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))
                : HttpRequest.BodyPublishers.ofByteArray(bytes);
        assertThat(publisher.contentLength()).isEqualTo(chunked ? -1 : size);
        var response = send("PUT", endpoint(teamId, REPO_KEY), bearer(), publisher, "application/json");
        if (size == LIMIT) {
            success(response);
            assertThat(observationCount()).isEqualTo(1);
        } else {
            error(response, 413, "PAYLOAD_TOO_LARGE");
            assertNoWrites();
        }
    }

    @Test
    void rejectedUpdatesKeepExistingSnapshotAndTimestamps() throws Exception {
        success(put(validBody()));
        Map<String, Object> before = stored();
        ObjectNode invalid = validBody();
        invalid.withArray("files").add(file(invalid).deepCopy());
        error(put(invalid), 400, "VALIDATION_FAILED");
        error(send("PUT", endpoint(teamId, REPO_KEY), bearer(),
                HttpRequest.BodyPublishers.ofByteArray(paddedBody(LIMIT + 1)), "application/json"),
                413, "PAYLOAD_TOO_LARGE");
        assertThat(stored()).isEqualTo(before);
    }

    @Test
    void temporaryAndFormalEndpointsShareTheSameObservationDuringTransition() throws Exception {
        String temporaryPath = "/api/v1/teams/" + teamId + "/git/dependency-graph/working-files/" + REPO_KEY;
        success(send("PUT", temporaryPath, bearer(), publisher(validBody()), "application/json"));
        Object repoId = stored().get("repo_id");
        ObjectNode changed = validBody().put("branch", "feature/formal");
        file(changed).put("relativePath", "src/Formal.tsx");
        success(put(changed));
        assertThat(stored().get("repo_id")).isEqualTo(repoId);
        assertThat(stored().get("branch_name")).isEqualTo("feature/formal");
        ObjectNode empty = json.createObjectNode().putNull("branch");
        empty.putArray("files");
        success(send("PUT", temporaryPath, bearer(), publisher(empty), "application/json"));
        assertThat(json.readTree(stored().get("files").toString()).isEmpty()).isTrue();
        assertThat(observationCount()).isEqualTo(1);
    }

    @Test
    void browserSessionCannotReplaceAgentTokenAndIdentityIsNotOverwritten() throws Exception {
        String email = UUID.randomUUID() + "@working-session.test";
        String password = "Working-http-test-123!";
        User browserUser = users.save(User.create(email, "웹 사용자", null, passwords.encode(password)));
        try (HttpClient browser = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()) {
            JsonNode csrf = success(browser.send(builder("/api/v1/auth/csrf", null, null).GET().build(),
                    HttpResponse.BodyHandlers.ofString()));
            success(browser.send(builder("/api/v1/auth/login", null, "application/json")
                    .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                    .POST(publisher(json.createObjectNode().put("email", email).put("password", password))).build(),
                    HttpResponse.BodyHandlers.ofString()));
            csrf = success(browser.send(builder("/api/v1/auth/csrf", null, null).GET().build(),
                    HttpResponse.BodyHandlers.ofString()));
            error(browser.send(builder(endpoint(teamId, REPO_KEY), null, "application/json")
                    .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                    .PUT(publisher(validBody())).build(), HttpResponse.BodyHandlers.ofString()),
                    401, "AUTHENTICATION_REQUIRED");
            error(browser.send(builder(endpoint(teamId, REPO_KEY), "Bearer invalid-token", "application/json")
                    .PUT(publisher(validBody())).build(), HttpResponse.BodyHandlers.ofString()),
                    401, "AUTHENTICATION_REQUIRED");
            assertNoWrites();
            success(browser.send(builder(endpoint(teamId, REPO_KEY), bearer(), "application/json")
                    .PUT(publisher(validBody())).build(), HttpResponse.BodyHandlers.ofString()));
            assertThat(stored().get("user_id")).isEqualTo(ownerId);
            JsonNode currentUser = success(browser.send(builder("/api/v1/auth/users/me", null, null).GET().build(),
                    HttpResponse.BodyHandlers.ofString()));
            assertThat(currentUser.path("userId").asLong()).isEqualTo(browserUser.getId());
        }
    }

    private ObjectNode validBody() {
        ObjectNode body = json.createObjectNode().put("branch", "feature/login");
        body.putArray("files").addObject().put("relativePath", "src/App.tsx").put("changeType", "MODIFIED");
        return body;
    }
    private ObjectNode file(ObjectNode body) { return (ObjectNode) body.at("/files/0"); }
    private String bearer() { return "Bearer " + token; }
    private String endpoint(Long team, String key) { return "/api/v1/teams/" + team + "/git/working-files/" + key; }
    private HttpResponse<String> put(ObjectNode body) throws Exception {
        return send("PUT", endpoint(teamId, REPO_KEY), bearer(), publisher(body), "application/json");
    }
    private HttpRequest.BodyPublisher publisher(ObjectNode body) throws Exception {
        return HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body));
    }
    private byte[] paddedBody(int size) throws Exception {
        ObjectNode body = validBody();
        file(body).put("relativePath", "src/" + "한".repeat(100) + ".tsx");
        String raw = json.writeValueAsString(body);
        byte[] bytes = (raw + " ".repeat(size - raw.getBytes(StandardCharsets.UTF_8).length)).getBytes(StandardCharsets.UTF_8);
        assertThat(bytes).hasSize(size);
        return bytes;
    }
    private HttpResponse<String> send(String method, String path, String authorization,
            HttpRequest.BodyPublisher body, String contentType) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(builder(path, authorization, contentType).method(method, body).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
    private HttpRequest.Builder builder(String path, String authorization, String contentType) {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json");
        if (authorization != null) builder.header("Authorization", authorization);
        if (contentType != null) builder.header("Content-Type", contentType);
        return builder;
    }
    private JsonNode success(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as("Response: %s", response.body()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("code").isNull()).isTrue();
        assertThat(body.path("message").isNull()).isTrue();
        return body.path("data");
    }
    private void error(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as("Response: %s", response.body()).isEqualTo(status);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("data").isNull()).isTrue();
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("message").asText()).isNotBlank();
    }
    private Map<String, Object> stored() {
        return jdbc.queryForMap("""
                select w.* from working_files w join team_repositories r on r.id = w.repo_id
                where r.team_id = ? and r.origin_url_sha256 = ? and w.user_id = ?
                """, teamId, REPO_KEY, ownerId);
    }
    private long repositoryCount() {
        return jdbc.queryForObject("select count(*) from team_repositories where team_id = ?", Long.class, teamId);
    }
    private long observationCount() {
        return jdbc.queryForObject("""
                select count(*) from working_files w join team_repositories r on r.id = w.repo_id where r.team_id = ?
                """, Long.class, teamId);
    }
    private void assertNoWrites() {
        assertThat(repositoryCount()).isZero();
        assertThat(observationCount()).isZero();
    }
    private static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
