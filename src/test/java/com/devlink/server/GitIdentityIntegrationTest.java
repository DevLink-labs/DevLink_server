package com.devlink.server;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import com.devlink.server.repository.UserGitSettingRepository;
import com.devlink.server.service.GitEmailHasher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

class GitIdentityIntegrationTest extends AbstractIntegrationTest {
    private static final String USER_PATH = "/api/v1/auth/users/me/git-identity";
    private static final String AGENT_PATH = "/api/v1/agents/me/git-identity";

    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private UserGitSettingRepository settings;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void userCanRegisterReadReplaceAndIdempotentlyDeleteHashOnlyIdentity() throws Exception {
        try (var browser = new Browser()) {
            long userId = browser.signupAndLogin();
            JsonNode unregistered = assertIdentitySuccess(browser.get(USER_PATH));
            assertStatus(unregistered, false);

            String email = newGitEmail();
            JsonNode registered = assertIdentitySuccess(browser.mutate("PUT", USER_PATH,
                    Map.of("gitEmail", "  " + email.toUpperCase(Locale.ROOT) + "  ")));
            assertStatus(registered, true);
            Instant registeredAt = Instant.parse(registered.path("registeredAt").asText());
            String expectedHash = GitEmailHasher.hash(email);
            assertThat(settings.findById(userId).orElseThrow().getGitEmailHash()).isEqualTo(expectedHash);
            assertThat(jdbc.queryForObject("select git_email_hash from user_git_settings where user_id = ?",
                    String.class, userId)).isEqualTo(expectedHash).matches("[0-9a-f]{64}");
            assertThat(jdbc.queryForObject("""
                    select count(*) from information_schema.columns
                    where table_schema = current_schema() and table_name = 'user_git_settings'
                      and column_name = 'git_email'
                    """, Integer.class)).isZero();
            assertThat(registered.has("gitEmail")).isFalse();
            assertThat(registered.has("gitEmailHash")).isFalse();

            JsonNode read = assertIdentitySuccess(browser.get(USER_PATH));
            assertThat(Instant.parse(read.path("registeredAt").asText())).isEqualTo(registeredAt);
            JsonNode unchanged = assertIdentitySuccess(browser.mutate("PUT", USER_PATH,
                    Map.of("gitEmail", email)));
            assertThat(Instant.parse(unchanged.path("registeredAt").asText())).isEqualTo(registeredAt);

            Instant previousRegistration = Instant.parse("2020-01-01T00:00:00Z");
            jdbc.update("update user_git_settings set registered_at = ? where user_id = ?",
                    Timestamp.from(previousRegistration), userId);
            String replacement = newGitEmail();
            JsonNode changed = assertIdentitySuccess(browser.mutate("PUT", USER_PATH,
                    Map.of("gitEmail", replacement)));
            assertThat(Instant.parse(changed.path("registeredAt").asText())).isAfter(previousRegistration);
            assertThat(settings.findById(userId).orElseThrow().getGitEmailHash())
                    .isEqualTo(GitEmailHasher.hash(replacement)).isNotEqualTo(expectedHash);
            assertThat(assertIdentitySuccess(browser.get(USER_PATH))).isEqualTo(changed);

            assertStatus(assertIdentitySuccess(browser.mutate("DELETE", USER_PATH, null)), false);
            assertThat(settings.existsById(userId)).isFalse();
            assertStatus(assertIdentitySuccess(browser.mutate("DELETE", USER_PATH, null)), false);
            assertStatus(assertIdentitySuccess(browser.get(USER_PATH)), false);
        }
    }

    @Test
    void agentUsesTokenOwnerAndVerifiesNormalizedEmailWithoutChangingIdentity() throws Exception {
        try (var owner = new Browser(); var anotherSession = new Browser()) {
            long ownerId = owner.signupAndLogin();
            String token = owner.createAgentToken();
            long anotherId = anotherSession.signupAndLogin();

            JsonNode absent = assertIdentitySuccess(anotherSession.bearer("GET", AGENT_PATH, null, token));
            assertAgentIdentity(absent, false, null);
            JsonNode absentVerification = assertIdentitySuccess(anotherSession.bearer("POST", AGENT_PATH + "/verify",
                    Map.of("gitEmail", newGitEmail()), token));
            assertThat(absentVerification.path("registered").asBoolean()).isFalse();
            assertThat(absentVerification.path("matched").asBoolean()).isFalse();

            String ownerEmail = newGitEmail();
            String anotherEmail = newGitEmail();
            assertIdentitySuccess(owner.mutate("PUT", USER_PATH, Map.of("gitEmail", ownerEmail)));
            assertIdentitySuccess(anotherSession.mutate("PUT", USER_PATH, Map.of("gitEmail", anotherEmail)));

            JsonNode identity = assertIdentitySuccess(anotherSession.bearer("GET", AGENT_PATH, null, token));
            assertAgentIdentity(identity, true, GitEmailHasher.hash(ownerEmail));
            assertThat(identity.has("gitEmail")).isFalse();
            JsonNode matched = assertIdentitySuccess(anotherSession.bearer("POST", AGENT_PATH + "/verify",
                    Map.of("gitEmail", "  " + ownerEmail.toUpperCase(Locale.ROOT) + " ",
                            "ownerUserId", anotherId), token));
            assertThat(matched.path("registered").asBoolean()).isTrue();
            assertThat(matched.path("matched").asBoolean()).isTrue();
            JsonNode mismatch = assertIdentitySuccess(anotherSession.bearer("POST", AGENT_PATH + "/verify",
                    Map.of("gitEmail", anotherEmail), token));
            assertThat(mismatch.path("registered").asBoolean()).isTrue();
            assertThat(mismatch.path("matched").asBoolean()).isFalse();
            assertThat(settings.findById(ownerId).orElseThrow().getGitEmailHash())
                    .isEqualTo(GitEmailHasher.hash(ownerEmail));
            assertThat(settings.findById(anotherId).orElseThrow().getGitEmailHash())
                    .isEqualTo(GitEmailHasher.hash(anotherEmail));
            assertThat(assertSuccess(anotherSession.get("/api/v1/auth/users/me"), 200)
                    .path("userId").asLong()).isEqualTo(anotherId);

            assertIdentitySuccess(owner.mutate("DELETE", USER_PATH, null));
            assertAgentIdentity(assertIdentitySuccess(anotherSession.bearer("GET", AGENT_PATH, null, token)), false, null);
        }
    }

    @Test
    void sessionAndAgentAuthenticationStaySeparateAndUserMutationsRequireCsrf() throws Exception {
        try (var owner = new Browser(); var anonymous = new Browser()) {
            assertError(anonymous.get(USER_PATH), 401, "AUTHENTICATION_REQUIRED");
            assertError(anonymous.mutate("PUT", USER_PATH, Map.of("gitEmail", newGitEmail())),
                    401, "AUTHENTICATION_REQUIRED");

            long userId = owner.signupAndLogin();
            String token = owner.createAgentToken();
            Map<String, String> body = Map.of("gitEmail", newGitEmail());
            assertError(owner.send("PUT", USER_PATH, body, null, null), 403, "ACCESS_DENIED");
            assertError(owner.send("DELETE", USER_PATH, null, null, null), 403, "ACCESS_DENIED");
            assertError(owner.get(AGENT_PATH), 401, "AUTHENTICATION_REQUIRED");
            assertError(owner.mutate("POST", AGENT_PATH + "/verify", body), 401, "AUTHENTICATION_REQUIRED");
            assertError(anonymous.bearer("GET", USER_PATH, null, token), 401, "AUTHENTICATION_REQUIRED");
            assertError(anonymous.bearer("GET", AGENT_PATH, null, "invalid-token"),
                    401, "AUTHENTICATION_REQUIRED");
            assertThat(settings.existsById(userId)).isFalse();
        }
    }

    @Test
    void anotherUsersNormalizedEmailCannotReplaceExistingRegistration() throws Exception {
        try (var first = new Browser(); var second = new Browser()) {
            long firstId = first.signupAndLogin();
            long secondId = second.signupAndLogin();
            String claimedEmail = newGitEmail();
            String secondEmail = newGitEmail();
            assertIdentitySuccess(first.mutate("PUT", USER_PATH, Map.of("gitEmail", claimedEmail)));
            JsonNode before = assertIdentitySuccess(second.mutate("PUT", USER_PATH, Map.of("gitEmail", secondEmail)));

            assertError(second.mutate("PUT", USER_PATH,
                    Map.of("gitEmail", " " + claimedEmail.toUpperCase(Locale.ROOT) + " ")),
                    409, "GIT_EMAIL_ALREADY_REGISTERED");

            assertThat(assertIdentitySuccess(second.get(USER_PATH))).isEqualTo(before);
            assertThat(settings.findById(firstId).orElseThrow().getGitEmailHash())
                    .isEqualTo(GitEmailHasher.hash(claimedEmail));
            assertThat(settings.findById(secondId).orElseThrow().getGitEmailHash())
                    .isEqualTo(GitEmailHasher.hash(secondEmail));
        }
    }

    @Test
    void invalidEmailInputsAreRejectedWithoutPersistingAnIdentity() throws Exception {
        try (var browser = new Browser()) {
            long userId = browser.signupAndLogin();
            String token = browser.createAgentToken();
            List<Map<String, Object>> invalid = List.of(
                    Map.of(),
                    Map.of("gitEmail", " "),
                    Map.of("gitEmail", "not-an-email"),
                    Map.of("gitEmail", 123),
                    Map.of("gitEmail", "a".repeat(256) + "@example.com"));

            for (Map<String, Object> body : invalid) {
                assertError(browser.mutate("PUT", USER_PATH, body), 400, "VALIDATION_FAILED");
                assertError(browser.bearer("POST", AGENT_PATH + "/verify", body, token),
                        400, "VALIDATION_FAILED");
            }
            assertThat(settings.existsById(userId)).isFalse();
        }
    }

    private static String newGitEmail() {
        return "git-" + UUID.randomUUID() + "@example.com";
    }

    private JsonNode assertIdentitySuccess(HttpResult response) {
        assertThat(response.cacheControl()).contains("no-store");
        return assertSuccess(response, 200);
    }

    private JsonNode assertSuccess(HttpResult response, int status) {
        assertThat(response.statusCode()).as("HTTP response: %s", response.body()).isEqualTo(status);
        assertThat(response.body().path("success").asBoolean()).isTrue();
        assertThat(response.body().path("code").isNull()).isTrue();
        assertThat(response.body().path("message").isNull()).isTrue();
        return response.body().path("data");
    }

    private void assertError(HttpResult response, int status, String code) {
        assertThat(response.statusCode()).as("HTTP response: %s", response.body()).isEqualTo(status);
        assertThat(response.body().path("success").asBoolean()).isFalse();
        assertThat(response.body().path("data").isNull()).isTrue();
        assertThat(response.body().path("code").asText()).isEqualTo(code);
    }

    private static void assertStatus(JsonNode data, boolean registered) {
        assertThat(data.path("registered").asBoolean()).isEqualTo(registered);
        assertThat(data.has("registeredAt")).isTrue();
        if (registered) {
            assertThat(data.path("registeredAt").asText()).isNotBlank();
        } else {
            assertThat(data.path("registeredAt").isNull()).isTrue();
        }
    }

    private static void assertAgentIdentity(JsonNode data, boolean registered, String expectedHash) {
        assertThat(data.path("registered").asBoolean()).isEqualTo(registered);
        assertThat(data.path("hashAlgorithm").asText()).isEqualTo("SHA-256");
        assertThat(data.path("normalization").asText()).isEqualTo("TRIM_LOWERCASE");
        assertThat(data.has("gitEmailHash")).isTrue();
        if (registered) {
            assertThat(data.path("gitEmailHash").asText()).isEqualTo(expectedHash);
        } else {
            assertThat(data.path("gitEmailHash").isNull()).isTrue();
        }
    }

    private record HttpResult(int statusCode, JsonNode body, String cacheControl) { }
    private record Csrf(String headerName, String token) { }

    private final class Browser implements AutoCloseable {
        private final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build();

        private long signupAndLogin() throws Exception {
            String email = "identity-" + UUID.randomUUID() + "@example.com";
            String password = "ValidPassword123!";
            Csrf csrf = csrf();
            assertSuccess(send("POST", "/api/v1/auth/signup", Map.of(
                    "email", email, "password", password, "name", "Identity owner"), csrf, null), 201);
            JsonNode login = assertSuccess(send("POST", "/api/v1/auth/login",
                    Map.of("email", email, "password", password), csrf, null), 200);
            return login.path("userId").asLong();
        }

        private String createAgentToken() throws Exception {
            long teamId = assertSuccess(mutate("POST", "/api/v1/teams",
                    Map.of("name", "Identity team " + UUID.randomUUID())), 201).path("teamId").asLong();
            long agentId = assertSuccess(mutate("POST", "/api/v1/agents/teams/" + teamId, Map.of(
                    "name", "Identity Agent", "toolType", "CODEX", "projectName", "identity")), 201)
                    .path("agentId").asLong();
            return assertSuccess(mutate("POST", "/api/v1/agents/" + agentId + "/token", null), 201)
                    .path("token").asText();
        }

        private HttpResult get(String path) throws Exception {
            return send("GET", path, null, null, null);
        }

        private HttpResult mutate(String method, String path, Object body) throws Exception {
            return send(method, path, body, csrf(), null);
        }

        private HttpResult bearer(String method, String path, Object body, String token) throws Exception {
            return send(method, path, body, null, token);
        }

        private Csrf csrf() throws Exception {
            JsonNode data = assertSuccess(get("/api/v1/auth/csrf"), 200);
            return new Csrf(data.path("headerName").asText(), data.path("token").asText());
        }

        private HttpResult send(String method, String path, Object body, Csrf csrf, String token) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(10)).header("Accept", "application/json");
            if (csrf != null) request.header(csrf.headerName(), csrf.token());
            if (token != null) request.header("Authorization", "Bearer " + token);
            if (body != null) request.header("Content-Type", "application/json");
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new HttpResult(response.statusCode(), json.readTree(response.body()),
                    response.headers().firstValue("Cache-Control").orElse(""));
        }

        @Override
        public void close() {
            client.close();
        }
    }
}
