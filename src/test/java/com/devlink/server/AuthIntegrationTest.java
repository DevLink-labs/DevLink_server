package com.devlink.server;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.teammember.TeamMember;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TeamMemberRepository;
import com.devlink.server.repository.TeamRepository;
import com.devlink.server.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "devlink.cors.allowed-origins=https://devlinkfrontend.vercel.app,https://devlink.ai.kr")
class AuthIntegrationTest extends AbstractIntegrationTest {
    private final java.util.List<OnlineTestBroker> onlineBrokers = new java.util.ArrayList<>();
    @org.junit.jupiter.api.AfterEach
    void closeOnlineBrokers() { onlineBrokers.forEach(OnlineTestBroker::close); }
    private void online(long id, String token) throws Exception {
        onlineBrokers.add(OnlineTestBroker.connect(port, token, id, agents));
    }
    private static final String PASSWORD = "ValidPassword123!";

    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwords;
    @Autowired private TeamRepository teams;
    @Autowired private TeamMemberRepository members;
    @Autowired private AgentRepository agents;
    @Autowired private TaskRepository tasks;

    @Test
    void signupNormalizesEmailStoresPasswordHashAndRequiresSeparateLogin() throws Exception {
        String email = "Mixed." + UUID.randomUUID() + "@EXAMPLE.COM";
        String normalizedEmail = email.toLowerCase(Locale.ROOT);
        try (var browser = new Browser()) {
            var csrf = browser.csrf();
            assertThat(csrf.headerName()).isEqualTo("X-CSRF-TOKEN");
            assertThat(csrf.parameterName()).isEqualTo("_csrf");
            assertThat(browser.sessionId()).isNotBlank();

            var signup = browser.post("/api/v1/auth/signup",
                    signupBody(email, PASSWORD, "Alice", "alice"), csrf);
            assertSuccess(signup, 201);
            var data = signup.body().path("data");
            assertThat(data.path("userId").asLong()).isPositive();
            assertThat(data.path("email").asText()).isEqualTo(normalizedEmail);
            assertThat(data.path("name").asText()).isEqualTo("Alice");
            assertThat(data.path("nickname").asText()).isEqualTo("alice");
            assertThat(data.path("createdAt").asText()).isNotBlank();
            assertThat(data.has("password")).isFalse();
            assertThat(data.has("passwordHash")).isFalse();

            var stored = users.findByEmail(normalizedEmail).orElseThrow();
            assertThat(stored.getId()).isEqualTo(data.path("userId").asLong());
            assertThat(stored.getPassword()).isNotEqualTo(PASSWORD);
            assertThat(passwords.matches(PASSWORD, stored.getPassword())).isTrue();
            assertError(browser.get("/api/v1/auth/users/me"), 401, "AUTHENTICATION_REQUIRED");
        }
    }

    @Test
    void duplicateEmailIsRejectedAfterNormalizationWithoutReplacingExistingUser() throws Exception {
        String email = newEmail();
        try (var browser = new Browser()) {
            var csrf = browser.csrf();
            var signup = browser.post("/api/v1/auth/signup",
                    signupBody(email, PASSWORD, "Original", "original"), csrf);
            assertSuccess(signup, 201);

            var duplicate = browser.post("/api/v1/auth/signup",
                    signupBody(email.toUpperCase(Locale.ROOT), "AnotherPassword123!", "Other", "other"), csrf);
            assertError(duplicate, 409, "EMAIL_ALREADY_EXISTS");
            var stored = users.findByEmail(email).orElseThrow();
            assertThat(stored.getId()).isEqualTo(signup.body().path("data").path("userId").asLong());
            assertThat(stored.getName()).isEqualTo("Original");
            assertThat(passwords.matches(PASSWORD, stored.getPassword())).isTrue();
        }
    }

    @ParameterizedTest
    @MethodSource("invalidSignups")
    void invalidSignupIsRejectedBeforeSavingUser(Map<String, Object> body) throws Exception {
        try (var browser = new Browser()) {
            var response = browser.post("/api/v1/auth/signup", body, browser.csrf());
            assertError(response, 400, "VALIDATION_FAILED");
            String email = (String) body.get("email");
            if (email != null) {
                assertThat(users.findByEmail(email.toLowerCase(Locale.ROOT))).isEmpty();
            }
        }
    }

    @Test
    void loginRotatesSessionRefreshesCsrfAndLogoutInvalidatesSessionAndCookie() throws Exception {
        String email = newEmail();
        try (var browser = new Browser()) {
            var csrfBeforeLogin = browser.csrf();
            var signup = browser.post("/api/v1/auth/signup",
                    signupBody(email, PASSWORD, "Alice", "alice"), csrfBeforeLogin);
            assertSuccess(signup, 201);
            long userId = signup.body().path("data").path("userId").asLong();
            String anonymousSessionId = browser.sessionId();

            var login = browser.post("/api/v1/auth/login",
                    loginBody(email.toUpperCase(Locale.ROOT), PASSWORD), csrfBeforeLogin);
            assertSuccess(login, 200);
            assertThat(login.body().path("data").path("userId").asLong()).isEqualTo(userId);
            assertThat(login.body().path("data").path("email").asText()).isEqualTo(email);
            assertThat(login.body().path("data").has("password")).isFalse();
            String authenticatedSessionId = browser.sessionId();
            assertThat(authenticatedSessionId).isNotBlank().isNotEqualTo(anonymousSessionId);

            var me = browser.get("/api/v1/auth/users/me");
            assertSuccess(me, 200);
            assertThat(me.body().path("data").path("userId").asLong()).isEqualTo(userId);
            assertError(getWithSession(anonymousSessionId), 401, "AUTHENTICATION_REQUIRED");
            assertError(browser.post("/api/v1/auth/logout", null, csrfBeforeLogin), 403, "ACCESS_DENIED");

            var csrfAfterLogin = browser.csrf();
            var logout = browser.post("/api/v1/auth/logout", null, csrfAfterLogin);
            assertSuccess(logout, 200);
            assertThat(logout.body().path("data").isNull()).isTrue();
            assertThat(logout.headers().allValues("Set-Cookie"))
                    .anySatisfy(cookie -> {
                        assertThat(cookie).startsWith("JSESSIONID=");
                        assertThat(cookie.toLowerCase(Locale.ROOT)).contains("max-age=0");
                    });
            assertError(browser.get("/api/v1/auth/users/me"), 401, "AUTHENTICATION_REQUIRED");
            assertError(getWithSession(authenticatedSessionId), 401, "AUTHENTICATION_REQUIRED");
        }
    }

    @Test
    void switchingAccountsInSameBrowserReplacesAuthenticatedIdentityAndOldSession() throws Exception {
        String firstEmail = newEmail();
        String secondEmail = newEmail();
        try (var browser = new Browser()) {
            var csrf = browser.csrf();
            var firstSignup = browser.post("/api/v1/auth/signup",
                    signupBody(firstEmail, PASSWORD, "First", "first"), csrf);
            var secondSignup = browser.post("/api/v1/auth/signup",
                    signupBody(secondEmail, PASSWORD, "Second", "second"), csrf);
            assertSuccess(firstSignup, 201);
            assertSuccess(secondSignup, 201);

            assertSuccess(browser.post("/api/v1/auth/login", loginBody(firstEmail, PASSWORD), csrf), 200);
            String firstSessionId = browser.sessionId();
            var secondLogin = browser.post("/api/v1/auth/login",
                    loginBody(secondEmail, PASSWORD), browser.csrf());
            assertSuccess(secondLogin, 200);
            long secondUserId = secondSignup.body().path("data").path("userId").asLong();
            assertThat(secondLogin.body().path("data").path("userId").asLong()).isEqualTo(secondUserId);
            assertThat(browser.sessionId()).isNotEqualTo(firstSessionId);
            var me = browser.get("/api/v1/auth/users/me");
            assertSuccess(me, 200);
            assertThat(me.body().path("data").path("userId").asLong()).isEqualTo(secondUserId);
            assertThat(me.body().path("data").path("email").asText()).isEqualTo(secondEmail);
            assertError(getWithSession(firstSessionId), 401, "AUTHENTICATION_REQUIRED");
        }
    }

    @Test
    void twoUsersKeepIndependentSessionsAndOneLogoutDoesNotAffectTheOther() throws Exception {
        String firstEmail = newEmail();
        String secondEmail = newEmail();
        try (var first = new Browser(); var second = new Browser()) {
            long firstId = signupAndLogin(first, firstEmail, "First");
            long secondId = signupAndLogin(second, secondEmail, "Second");
            assertThat(firstId).isNotEqualTo(secondId);
            assertThat(first.sessionId()).isNotEqualTo(second.sessionId());
            assertThat(first.get("/api/v1/auth/users/me").body().path("data").path("userId").asLong())
                    .isEqualTo(firstId);
            assertThat(second.get("/api/v1/auth/users/me").body().path("data").path("userId").asLong())
                    .isEqualTo(secondId);

            assertSuccess(first.post("/api/v1/auth/logout", null, first.csrf()), 200);
            assertError(first.get("/api/v1/auth/users/me"), 401, "AUTHENTICATION_REQUIRED");
            var secondMe = second.get("/api/v1/auth/users/me");
            assertSuccess(secondMe, 200);
            assertThat(secondMe.body().path("data").path("userId").asLong()).isEqualTo(secondId);
        }
    }

    @Test
    void logoutDoesNotRevokeAgentTokenOrRestoreUserSessionWhenCreatingTask() throws Exception {
        try (var browser = new Browser()) {
            long userId = signupAndLogin(browser, newEmail(), "Task owner");
            var team = teams.saveAndFlush(Team.create("Auth task team", UUID.randomUUID().toString()));
            members.saveAndFlush(TeamMember.create(team.getId(), userId, MemberRole.OWNER));
            var requester = agents.saveAndFlush(Agent.create(team.getId(), userId,
                    "Requester", null, ToolType.CODEX, "auth-project"));
            var receiver = agents.saveAndFlush(Agent.create(team.getId(), userId,
                    "Receiver", null, ToolType.CLAUDE_CODE, "auth-project"));
            var issued = browser.post("/api/v1/agents/" + requester.getId() + "/token", null, browser.csrf());
            assertSuccess(issued, 201);
            String token = issued.body().path("data").path("token").asText();
            var receiverToken = browser.post("/api/v1/agents/" + receiver.getId() + "/token", null, browser.csrf());
            assertSuccess(receiverToken, 201);
            online(receiver.getId(), receiverToken.body().path("data").path("token").asText());
            assertSuccess(browser.post("/api/v1/auth/logout", null, browser.csrf()), 200);

            var response = browser.postWithAgentToken("/api/v1/tasks/" + team.getId(), Map.of(
                    "requestKey", UUID.randomUUID().toString(),
                    "receiverAgentId", receiver.getId(),
                    "prompt", "Verify authenticated task creation"), token);

            assertSuccess(response, 201);
            long taskId = response.body().path("data").path("taskId").asLong();
            assertThat(taskId).isPositive();
            var task = tasks.findById(taskId).orElseThrow();
            assertThat(task.getRequesterUserId()).isEqualTo(userId);
            assertThat(task.getRequesterAgentId()).isEqualTo(requester.getId());
            assertError(browser.get("/api/v1/auth/users/me"), 401, "AUTHENTICATION_REQUIRED");
        }
    }

    @Test
    void agentBearerDeterminesTaskIdentityWithoutChangingUserSession() throws Exception {
        try (var owner = new Browser(); var other = new Browser()) {
            long ownerId = signupAndLogin(owner, newEmail(), "Task owner");
            long otherId = signupAndLogin(other, newEmail(), "Other user");
            var team = teams.saveAndFlush(Team.create("Task permission team", UUID.randomUUID().toString()));
            var ownerMembership = members.saveAndFlush(TeamMember.create(team.getId(), ownerId, MemberRole.OWNER));
            var requester = agents.saveAndFlush(Agent.create(team.getId(), ownerId,
                    "Requester", null, ToolType.CODEX, "auth-project"));
            var receiver = agents.saveAndFlush(Agent.create(team.getId(), ownerId,
                    "Receiver", null, ToolType.CLAUDE_CODE, "auth-project"));
            var issued = owner.post("/api/v1/agents/" + requester.getId() + "/token", null, owner.csrf());
            assertSuccess(issued, 201);
            String token = issued.body().path("data").path("token").asText();
            String requestKey = UUID.randomUUID().toString();
            String path = "/api/v1/tasks/" + team.getId();
            var receiverToken = owner.post("/api/v1/agents/" + receiver.getId() + "/token", null, owner.csrf());
            assertSuccess(receiverToken, 201);
            online(receiver.getId(), receiverToken.body().path("data").path("token").asText());
            Map<String, Object> body = Map.of("requestKey", requestKey,
                    "receiverAgentId", receiver.getId(),
                    "prompt", "Verify permission before existing task controller");

            String otherSessionId = other.sessionId();
            var created = other.postWithAgentToken(path, body, token);
            assertSuccess(created, 201);
            long taskId = created.body().path("data").path("taskId").asLong();
            var task = tasks.findById(taskId).orElseThrow();
            assertThat(task.getRequesterUserId()).isEqualTo(ownerId);
            assertThat(task.getRequesterAgentId()).isEqualTo(requester.getId());
            assertThat(task.getTeamId()).isEqualTo(team.getId());
            assertThat(other.sessionId()).isEqualTo(otherSessionId);
            var me = other.get("/api/v1/auth/users/me");
            assertSuccess(me, 200);
            assertThat(me.body().path("data").path("userId").asLong()).isEqualTo(otherId);

            members.saveAndFlush(TeamMember.create(team.getId(), otherId, MemberRole.MEMBER));
            var replayed = other.postWithAgentToken(path, body, token);
            assertSuccess(replayed, 200);
            assertThat(replayed.body().path("data").path("taskId").asLong()).isEqualTo(taskId);
            assertThat(tasks.findByRequesterAgentIdAndRequestKey(requester.getId(), requestKey))
                    .hasValueSatisfying(saved -> assertThat(saved.getId()).isEqualTo(taskId));
            assertError(owner.postWithAgentToken(path, Map.of("requestKey", requestKey,
                    "prompt", "Missing receiver agent ID"), token), 400, "VALIDATION_FAILED");
            assertError(owner.postWithAgentToken(path, null, token), 400, "VALIDATION_FAILED");

            members.deleteById(ownerMembership.getId());
            assertError(owner.postWithAgentToken(path, body, token), 401, "AUTHENTICATION_REQUIRED");
            assertThat(tasks.findById(taskId)).isPresent();
        }
    }

    @Test
    void unknownEmailAndWrongPasswordReturnSameUnauthorizedResponseWithoutLogin() throws Exception {
        String email = newEmail();
        try (var browser = new Browser()) {
            var csrf = browser.csrf();
            assertSuccess(browser.post("/api/v1/auth/signup",
                    signupBody(email, PASSWORD, "Alice", "alice"), csrf), 201);

            var wrongPassword = browser.post("/api/v1/auth/login",
                    loginBody(email, "WrongPassword123!"), csrf);
            var unknownEmail = browser.post("/api/v1/auth/login",
                    loginBody(newEmail(), PASSWORD), browser.csrf());
            assertError(wrongPassword, 401, "INVALID_CREDENTIALS");
            assertError(unknownEmail, 401, "INVALID_CREDENTIALS");
            assertThat(wrongPassword.body().path("message").asText())
                    .isEqualTo(unknownEmail.body().path("message").asText());
            assertError(browser.get("/api/v1/auth/users/me"), 401, "AUTHENTICATION_REQUIRED");
        }
    }

    @Test
    void invalidLoginBodyReturnsValidationError() throws Exception {
        try (var browser = new Browser()) {
            assertError(browser.post("/api/v1/auth/login", Map.of("email", "invalid-email"), browser.csrf()),
                    400, "VALIDATION_FAILED");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/logout"})
    void authenticationPostWithoutCsrfIsForbidden(String path) throws Exception {
        try (var browser = new Browser()) {
            assertError(browser.post(path, signupBody(newEmail(), PASSWORD, "Alice", "alice"), null),
                    403, "ACCESS_DENIED");
        }
    }

    @Test
    void unauthenticatedMeReturnsJsonUnauthorizedWithoutRedirect() throws Exception {
        try (var browser = new Browser()) {
            assertError(browser.get("/api/v1/auth/users/me"), 401, "AUTHENTICATION_REQUIRED");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://devlinkfrontend.vercel.app", "https://devlink.ai.kr"})
    void corsAllowsConfiguredFrontendOriginsWithCredentialsAndKeepsCsrfProtection(String origin) throws Exception {
        try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            var preflight = HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Origin", origin)
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "content-type,x-csrf-token")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
            var preflightResponse = client.send(preflight, HttpResponse.BodyHandlers.ofString());
            assertThat(preflightResponse.statusCode()).isEqualTo(200);
            assertCorsCredentials(preflightResponse, origin);
            assertThat(preflightResponse.headers().firstValue("Access-Control-Allow-Methods").orElse(""))
                    .contains("POST");
            assertThat(preflightResponse.headers().firstValue("Access-Control-Allow-Headers")
                    .orElse("").toLowerCase(Locale.ROOT)).contains("content-type", "x-csrf-token");

            var csrfRequest = HttpRequest.newBuilder(uri("/api/v1/auth/csrf"))
                    .timeout(Duration.ofSeconds(10)).header("Origin", origin).GET().build();
            var csrfResponse = client.send(csrfRequest, HttpResponse.BodyHandlers.ofString());
            assertSuccess(result(csrfResponse), 200);
            assertCorsCredentials(csrfResponse, origin);

            var loginWithoutCsrf = HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Origin", origin)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(loginBody(newEmail(), PASSWORD))))
                    .build();
            var loginResponse = client.send(loginWithoutCsrf, HttpResponse.BodyHandlers.ofString());
            assertError(result(loginResponse), 403, "ACCESS_DENIED");
            assertCorsCredentials(loginResponse, origin);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://untrusted.example", "https://devlink.ai.kr.evil.example",
            "https://devlinkfrontend.vercel.app.evil.example", "http://devlink.ai.kr"})
    void corsRejectsOriginsOutsideExactAllowlist(String origin) throws Exception {
        try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            var preflight = HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Origin", origin)
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "content-type,x-csrf-token")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
            var response = client.send(preflight, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
            assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
        }
    }

    private void assertCorsCredentials(HttpResponse<String> response, String origin) {
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(origin);
        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).contains("true");
    }

    private long signupAndLogin(Browser browser, String email, String name) throws Exception {
        var csrf = browser.csrf();
        var signup = browser.post("/api/v1/auth/signup", signupBody(email, PASSWORD, name, null), csrf);
        assertSuccess(signup, 201);
        assertThat(signup.body().path("data").path("nickname").isNull()).isTrue();
        var login = browser.post("/api/v1/auth/login", loginBody(email, PASSWORD), csrf);
        assertSuccess(login, 200);
        return login.body().path("data").path("userId").asLong();
    }

    private HttpResult getWithSession(String sessionId) throws Exception {
        try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            var request = HttpRequest.newBuilder(uri("/api/v1/auth/users/me"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("Cookie", "JSESSIONID=" + sessionId)
                    .GET().build();
            return result(client.send(request, HttpResponse.BodyHandlers.ofString()));
        }
    }

    private void assertSuccess(HttpResult response, int statusCode) {
        assertJsonResponse(response, statusCode);
        assertThat(response.body().path("success").asBoolean()).isTrue();
        assertThat(response.body().path("code").isNull()).isTrue();
        assertThat(response.body().path("message").isNull()).isTrue();
    }

    private void assertError(HttpResult response, int statusCode, String code) {
        assertJsonResponse(response, statusCode);
        assertThat(response.body().path("success").asBoolean()).isFalse();
        assertThat(response.body().path("data").isNull()).isTrue();
        assertThat(response.body().path("code").asText()).isEqualTo(code);
        assertThat(response.body().path("message").asText()).isNotBlank();
    }

    private void assertJsonResponse(HttpResult response, int statusCode) {
        assertThat(response.statusCode()).isEqualTo(statusCode);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/json");
    }

    private HttpResult result(HttpResponse<String> response) throws Exception {
        return new HttpResult(response.statusCode(), response.headers(), json.readTree(response.body()));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String newEmail() {
        return "auth-" + UUID.randomUUID() + "@example.com";
    }

    private static Map<String, Object> signupBody(String email, String password, String name, String nickname) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        body.put("name", name);
        body.put("nickname", nickname);
        return body;
    }

    private static Map<String, Object> loginBody(String email, String password) {
        return Map.of("email", email, "password", password);
    }

    private static Stream<Map<String, Object>> invalidSignups() {
        return Stream.of(
                signupBody("invalid-email", PASSWORD, "Alice", null),
                signupBody(null, PASSWORD, "Alice", null),
                signupBody(newEmail(), "short", "Alice", null),
                signupBody(newEmail(), null, "Alice", null),
                signupBody(newEmail(), "A".repeat(73), "Alice", null),
                signupBody(newEmail(), "가".repeat(25), "Alice", null),
                signupBody(newEmail(), PASSWORD, " ", null));
    }

    private record Csrf(String headerName, String parameterName, String token) { }

    private record HttpResult(int statusCode, HttpHeaders headers, JsonNode body) { }

    private final class Browser implements AutoCloseable {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        private HttpResult get(String path) throws Exception {
            return send("GET", path, null, null);
        }

        private HttpResult post(String path, Object body, Csrf csrf) throws Exception {
            return send("POST", path, body, csrf);
        }

        private HttpResult postWithAgentToken(String path, Object body, String token) throws Exception {
            return send("POST", path, body, null, token);
        }

        private Csrf csrf() throws Exception {
            var response = get("/api/v1/auth/csrf");
            assertSuccess(response, 200);
            var data = response.body().path("data");
            assertThat(data.path("headerName").asText()).isNotBlank();
            assertThat(data.path("parameterName").asText()).isNotBlank();
            assertThat(data.path("token").asText()).isNotBlank();
            return new Csrf(data.path("headerName").asText(),
                    data.path("parameterName").asText(), data.path("token").asText());
        }

        private HttpResult send(String method, String path, Object body, Csrf csrf) throws Exception {
            return send(method, path, body, csrf, null);
        }

        private HttpResult send(String method, String path, Object body, Csrf csrf, String token) throws Exception {
            var request = HttpRequest.newBuilder(uri(path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json");
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            if (csrf != null) {
                request.header(csrf.headerName(), csrf.token());
            }
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return result(client.send(request.build(), HttpResponse.BodyHandlers.ofString()));
        }

        private String sessionId() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> "JSESSIONID".equals(cookie.getName()) && !cookie.hasExpired())
                    .map(HttpCookie::getValue).findFirst().orElse("");
        }

        @Override
        public void close() {
            client.close();
        }
    }
}
