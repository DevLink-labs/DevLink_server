package com.devlink.server;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import com.devlink.server.domain.agent.AgentStatus;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.teammember.TeamMember;
import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskEventRepository;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TeamMemberRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class AgentTokenIntegrationTest extends AbstractIntegrationTest {
    private final java.util.List<OnlineTestBroker> onlineBrokers = new java.util.ArrayList<>();
    @org.junit.jupiter.api.AfterEach
    void closeOnlineBrokers() { onlineBrokers.forEach(OnlineTestBroker::close); }
    private void online(long id, String token) throws Exception {
        onlineBrokers.add(OnlineTestBroker.connect(port, token, id, agents));
    }
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private AgentRepository agents;
    @Autowired private TaskRepository tasks;
    @Autowired private TaskEventRepository taskEvents;
    @Autowired private TeamMemberRepository members;
    @Autowired private ConnectionRegistry connections;

    @Test
    void twoUsersJoinOneTeamRegisterAgentsAndCreateTaskUsingVerifiedRequesterIdentity() throws Exception {
        try (var first = new Browser(); var second = new Browser()) {
            long firstUserId = first.signupAndLogin();
            long secondUserId = second.signupAndLogin();
            var createdTeam = first.mutate("POST", "/api/v1/teams",
                    Map.of("name", "Two agent flow " + UUID.randomUUID()));
            assertSuccess(createdTeam, 201);
            long teamId = createdTeam.body().path("data").path("teamId").asLong();
            String inviteCode = createdTeam.body().path("data").path("inviteCode").asText();
            var joined = second.mutate("POST", "/api/v1/teams/join", Map.of("inviteCode", inviteCode));
            assertSuccess(joined, 201);
            assertThat(members.findByTeamIdAndUserId(teamId, firstUserId).orElseThrow().getRole())
                    .isEqualTo(MemberRole.OWNER);
            assertThat(members.findByTeamIdAndUserId(teamId, secondUserId).orElseThrow().getRole())
                    .isEqualTo(MemberRole.MEMBER);

            long firstAgentId = first.createAgent(teamId);
            long secondAgentId = second.createAgent(teamId);
            String firstToken = first.issueToken(firstAgentId);
            String secondToken = second.issueToken(secondAgentId);
            online(secondAgentId, secondToken);
            var listing = machine("GET", "/api/v1/agents/teams/" + teamId, null, firstToken);
            assertSuccess(listing, 200);
            assertThat(listing.body().path("data").findValuesAsText("agentId"))
                    .containsExactlyInAnyOrder(Long.toString(firstAgentId), Long.toString(secondAgentId));
            var secondContext = machine("GET", "/api/v1/agents/me", null, secondToken);
            assertSuccess(secondContext, 200);
            assertThat(secondContext.body().path("data").path("agentId").asLong()).isEqualTo(secondAgentId);

            String requestKey = UUID.randomUUID().toString();
            var createdTask = machine("POST", "/api/v1/tasks/" + teamId, Map.of(
                    "requestKey", requestKey,
                    "receiverAgentId", secondAgentId,
                    "requesterAgentId", secondAgentId,
                    "prompt", "Request work from the other team member's agent"), firstToken);

            assertSuccess(createdTask, 201);
            long taskId = createdTask.body().path("data").path("taskId").asLong();
            assertThat(taskId).isPositive();
            var task = tasks.findById(taskId).orElseThrow();
            assertThat(task.getRequestKey()).isEqualTo(requestKey);
            assertThat(task.getRequesterAgentId()).isEqualTo(firstAgentId);
            assertThat(task.getRequesterUserId()).isEqualTo(firstUserId);
            assertThat(task.getReceiverAgentId()).isEqualTo(secondAgentId);
            assertThat(task.getTeamId()).isEqualTo(teamId);
        }
    }

    @Test
    void taskCreationRequiresAgentTokenEvenWithLoggedInSessionAndCsrf() throws Exception {
        try (var owner = new Browser()) {
            long userId = owner.signupAndLogin();
            long teamId = owner.createTeam();
            long requesterId = owner.createAgent(teamId);
            long receiverId = owner.createAgent(teamId);
            owner.issueToken(requesterId);
            String requestKey = UUID.randomUUID().toString();
            String path = "/api/v1/tasks/" + teamId;
            Map<String, Object> body = Map.of("requestKey", requestKey,
                    "receiverAgentId", receiverId, "prompt", "Require an Agent credential");

            assertError(owner.mutate("POST", path, body), 401, "AUTHENTICATION_REQUIRED");
            assertError(machine("POST", path, body, null), 401, "AUTHENTICATION_REQUIRED");
            assertError(machine("POST", path, body, "unrecognized-token"),
                    401, "AUTHENTICATION_REQUIRED");
            assertThat(tasks.findByRequesterAgentIdAndRequestKey(requesterId, requestKey)).isEmpty();
        }
    }

    @Test
    void offlineReceiverReturns409WithoutSavingTaskOrCreatedEvent() throws Exception {
        try (var owner = new Browser()) {
            owner.signupAndLogin();
            long teamId = owner.createTeam();
            long requesterId = owner.createAgent(teamId);
            long receiverId = owner.createAgent(teamId);
            String token = owner.issueToken(requesterId);
            String key = UUID.randomUUID().toString();
            long tasksBefore = tasks.count();
            long eventsBefore = taskEvents.count();
            assertError(machine("POST", "/api/v1/tasks/" + teamId, Map.of(
                    "requestKey", key, "receiverAgentId", receiverId, "prompt", "Offline must not be stored"), token),
                    409, "RECEIVER_OFFLINE");
            assertThat(tasks.findByRequesterAgentIdAndRequestKey(requesterId, key)).isEmpty();
            assertThat(tasks.count()).isEqualTo(tasksBefore);
            assertThat(taskEvents.count()).isEqualTo(eventsBefore);
        }
    }

    @Test
    void taskCreationRejectsAnotherTeamPathAndAnotherTeamsReceiver() throws Exception {
        try (var owner = new Browser()) {
            long userId = owner.signupAndLogin();
            long teamId = owner.createTeam();
            long otherTeamId = owner.createTeam();
            long requesterId = owner.createAgent(teamId);
            long receiverId = owner.createAgent(teamId);
            long otherReceiverId = owner.createAgent(otherTeamId);
            String token = owner.issueToken(requesterId);
            String wrongTeamKey = UUID.randomUUID().toString();
            String wrongReceiverKey = UUID.randomUUID().toString();

            assertError(machine("POST", "/api/v1/tasks/" + otherTeamId, Map.of(
                    "requestKey", wrongTeamKey, "receiverAgentId", receiverId,
                    "prompt", "Use a team outside the credential context"), token),
                    403, "ACCESS_DENIED");
            assertError(machine("POST", "/api/v1/tasks/" + teamId, Map.of(
                    "requestKey", wrongReceiverKey, "receiverAgentId", otherReceiverId,
                    "prompt", "Use a receiver outside the credential team"), token),
                    404, "RESOURCE_NOT_FOUND");
            assertThat(tasks.findByRequesterAgentIdAndRequestKey(requesterId, wrongTeamKey)).isEmpty();
            assertThat(tasks.findByRequesterAgentIdAndRequestKey(requesterId, wrongReceiverKey)).isEmpty();
        }
    }

    @Test
    void taskUsesTokenIdentityDespiteSpoofedQueryAndOnlyReceiverTokenCanAccept() throws Exception {
        try (var owner = new Browser()) {
            long userId = owner.signupAndLogin();
            long teamId = owner.createTeam();
            long requesterId = owner.createAgent(teamId);
            long receiverId = owner.createAgent(teamId);
            String requesterToken = owner.issueToken(requesterId);
            String receiverToken = owner.issueToken(receiverId);
            online(receiverId, receiverToken);
            String requestKey = UUID.randomUUID().toString();

            var created = machine("POST", "/api/v1/tasks/" + teamId + "?agentId=" + receiverId, Map.of(
                    "requestKey", requestKey, "receiverAgentId", receiverId,
                    "requesterAgentId", receiverId, "prompt", "Use the authenticated requester"), requesterToken);
            assertSuccess(created, 201);
            long taskId = created.body().path("data").path("taskId").asLong();
            var before = tasks.findById(taskId).orElseThrow();
            assertThat(before.getRequesterAgentId()).isEqualTo(requesterId);
            assertThat(before.getRequesterUserId()).isEqualTo(userId);
            assertThat(before.getReceiverAgentId()).isEqualTo(receiverId);
            assertThat(before.getTeamId()).isEqualTo(teamId);
            assertThat(before.getStatus()).isEqualTo(TaskStatus.PENDING);
            long version = before.getVersion();
            int eventCount = taskEvents.findByTaskIdOrderByCreatedAtAsc(taskId).size();
            Map<String, Object> acceptance = Map.of("status", "IN_PROGRESS", "version", version);

            assertError(machine("PATCH", "/api/v1/tasks/" + taskId + "/status?agentId=" + receiverId,
                    acceptance, requesterToken), 403, "ACCESS_DENIED");
            var unchanged = tasks.findById(taskId).orElseThrow();
            assertThat(unchanged.getStatus()).isEqualTo(TaskStatus.PENDING);
            assertThat(unchanged.getVersion()).isEqualTo(version);
            assertThat(unchanged.getStartedAt()).isNull();
            assertThat(unchanged.getCompletedAt()).isNull();
            assertThat(taskEvents.findByTaskIdOrderByCreatedAtAsc(taskId)).hasSize(eventCount);

            var accepted = machine("PATCH", "/api/v1/tasks/" + taskId + "/status",
                    acceptance, receiverToken);
            assertSuccess(accepted, 200);
            var saved = tasks.findById(taskId).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
            assertThat(saved.getStartedAt()).isNotNull();
            assertThat(saved.getVersion()).isGreaterThan(version);
            assertThat(accepted.body().path("data").path("version").asLong()).isEqualTo(saved.getVersion());
        }
    }

    @Test
    void issuedTokenStoresOnlyHashAndAuthenticatesAgentContextAndList() throws Exception {
        try (var owner = new Browser()) {
            long userId = owner.signupAndLogin();
            long teamId = owner.createTeam();
            long agentId = owner.createAgent(teamId);
            long otherAgentId = owner.createAgent(teamId);
            String token = owner.issueToken(agentId);

            assertThat(token).matches("[A-Za-z0-9_-]{43}");
            assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
            String storedHash = agents.findById(agentId).orElseThrow().getConnectionTokenHash();
            assertThat(storedHash).matches("[0-9a-f]{64}").isEqualTo(hash(token)).isNotEqualTo(token);

            var context = machine("GET", "/api/v1/agents/me", null, token);
            assertSuccess(context, 200);
            JsonNode profile = context.body().path("data");
            assertThat(profile.path("agentId").asLong()).isEqualTo(agentId);
            assertThat(profile.path("ownerUserId").asLong()).isEqualTo(userId);
            assertThat(profile.path("teamId").asLong()).isEqualTo(teamId);
            assertThat(profile.has("token")).isFalse();
            assertThat(profile.has("connectionTokenHash")).isFalse();

            var listing = machine("GET", "/api/v1/agents/teams/" + teamId, null, token);
            assertSuccess(listing, 200);
            assertThat(listing.body()).isEqualTo(owner.get("/api/v1/agents/teams/" + teamId).body());
            assertThat(listing.body().path("data").findValuesAsText("agentId"))
                    .containsExactlyInAnyOrder(Long.toString(agentId), Long.toString(otherAgentId));
        }
    }

    @Test
    void rotationImmediatelyRejectsPreviouslyValidToken() throws Exception {
        try (var owner = new Browser()) {
            owner.signupAndLogin();
            long agentId = owner.createAgent(owner.createTeam());
            String firstToken = owner.issueToken(agentId);
            assertSuccess(machine("GET", "/api/v1/agents/me", null, firstToken), 200);

            String secondToken = owner.issueToken(agentId);

            assertThat(secondToken).isNotEqualTo(firstToken);
            assertThat(agents.findById(agentId).orElseThrow().getConnectionTokenHash()).isEqualTo(hash(secondToken));
            assertError(machine("GET", "/api/v1/agents/me", null, firstToken), 401, "AUTHENTICATION_REQUIRED");
            assertSuccess(machine("GET", "/api/v1/agents/me", null, secondToken), 200);
            assertSuccess(owner.get("/api/v1/auth/users/me"), 200);
        }
    }

    @Test
    void teamMemberCannotIssueAnotherUsersAgentCredential() throws Exception {
        try (var owner = new Browser(); var teammate = new Browser()) {
            owner.signupAndLogin();
            long teamId = owner.createTeam();
            long agentId = owner.createAgent(teamId);
            String originalToken = owner.issueToken(agentId);
            long teammateId = teammate.signupAndLogin();
            members.saveAndFlush(TeamMember.create(teamId, teammateId, MemberRole.MEMBER));

            assertError(teammate.mutate("POST", "/api/v1/agents/" + agentId + "/token", null),
                    403, "ACCESS_DENIED");
            assertThat(agents.findById(agentId).orElseThrow().getConnectionTokenHash()).isEqualTo(hash(originalToken));
            assertSuccess(machine("GET", "/api/v1/agents/me", null, originalToken), 200);
        }
    }

    @Test
    void tokenBecomesInvalidWhenOwnerNoLongerBelongsToAgentTeam() throws Exception {
        try (var owner = new Browser()) {
            long userId = owner.signupAndLogin();
            long teamId = owner.createTeam();
            long agentId = owner.createAgent(teamId);
            String token = owner.issueToken(agentId);
            var membership = members.findByTeamIdAndUserId(teamId, userId).orElseThrow();
            members.delete(membership);
            members.flush();

            assertError(machine("GET", "/api/v1/agents/me", null, token), 401, "AUTHENTICATION_REQUIRED");
            assertError(owner.mutate("POST", "/api/v1/agents/" + agentId + "/token", null),
                    403, "ACCESS_DENIED");
        }
    }

    @Test
    void userSessionAndAgentBearerRemainSeparateOutsideSharedListing() throws Exception {
        try (var owner = new Browser()) {
            owner.signupAndLogin();
            long teamId = owner.createTeam();
            long agentId = owner.createAgent(teamId);
            String token = owner.issueToken(agentId);

            assertError(owner.get("/api/v1/agents/me"), 401, "AUTHENTICATION_REQUIRED");
            assertSuccess(owner.get("/api/v1/agents/teams/" + teamId), 200);
            assertError(owner.get("/ws/broker"), 401, "AUTHENTICATION_REQUIRED");
            assertError(machine("GET", "/api/v1/auth/users/me", null, token), 401, "AUTHENTICATION_REQUIRED");
            assertSuccess(machine("GET", "/api/v1/agents/teams/" + teamId, null, token), 200);
            assertError(machine("GET", "/api/v1/agents/" + agentId, null, token),
                    401, "AUTHENTICATION_REQUIRED");
            assertError(machine("POST", "/api/v1/agents/teams/" + teamId, Map.of(
                    "name", "Another agent", "toolType", "CODEX", "projectName", "devlink"), token),
                    403, "ACCESS_DENIED");
            assertError(machine("GET", "/api/v1/agents/me", null, "unrecognized-token"),
                    401, "AUTHENTICATION_REQUIRED");
            assertError(owner.send("POST", "/api/v1/agents/" + agentId + "/token", null, null),
                    403, "ACCESS_DENIED");
        }
    }

    @Test
    void sharedListingUsesAgentHeaderWithoutChangingUserSession() throws Exception {
        try (var owner = new Browser(); var outsider = new Browser()) {
            owner.signupAndLogin();
            long teamId = owner.createTeam();
            String token = owner.issueToken(owner.createAgent(teamId));
            String path = "/api/v1/agents/teams/" + teamId;
            long outsiderId = outsider.signupAndLogin();
            String sessionId = outsider.sessionId();

            assertError(outsider.get(path), 403, "ACCESS_DENIED");
            assertSuccess(outsider.getWithAuthorization(path, "Bearer " + token), 200);
            assertThat(outsider.sessionId()).isEqualTo(sessionId);
            assertThat(outsider.get("/api/v1/auth/users/me").body().path("data").path("userId").asLong())
                    .isEqualTo(outsiderId);
            assertError(outsider.get(path), 403, "ACCESS_DENIED");

            assertError(owner.getWithAuthorization(path, "Bearer unrecognized-token"),
                    401, "AUTHENTICATION_REQUIRED");
            assertError(owner.getWithAuthorization(path, "Basic invalid"),
                    401, "AUTHENTICATION_REQUIRED");
            assertSuccess(owner.get(path), 200);
        }
    }

    @Test
    void agentListingCannotUseOwnersOtherTeamMembership() throws Exception {
        try (var owner = new Browser()) {
            owner.signupAndLogin();
            long teamId = owner.createTeam();
            long otherTeamId = owner.createTeam();
            String token = owner.issueToken(owner.createAgent(teamId));
            String otherTeamPath = "/api/v1/agents/teams/" + otherTeamId;

            assertSuccess(owner.get(otherTeamPath), 200);
            assertError(machine("GET", otherTeamPath, null, token), 403, "ACCESS_DENIED");
            assertError(owner.getWithAuthorization(otherTeamPath, "Bearer " + token), 403, "ACCESS_DENIED");
            assertError(machine("GET", "/api/v1/agents/teams/" + teamId, null, null),
                    401, "AUTHENTICATION_REQUIRED");
            assertError(machine("GET", "/api/v1/agents/teams/0", null, token),
                    400, "VALIDATION_FAILED");
            assertError(machine("GET", "/api/v1/agents/teams/invalid", null, token),
                    400, "VALIDATION_FAILED");
        }
    }

    @Test
    void tokenRotationClosesRealBrokerConnectionRemovesRegistryAndMarksAgentOffline() throws Exception {
        try (var owner = new Browser(); var socketClient = HttpClient.newHttpClient()) {
            owner.signupAndLogin();
            long agentId = owner.createAgent(owner.createTeam());
            String firstToken = owner.issueToken(agentId);
            var listener = new BrokerListener();
            var socket = socketClient.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + firstToken)
                    .header("Origin", "http://localhost:3000")
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws/broker"), listener)
                    .get(5, TimeUnit.SECONDS);
            try {
                var ready = json.readTree(listener.ready.get(5, TimeUnit.SECONDS));
                assertThat(ready.path("type").asText()).isEqualTo("BROKER_READY");
                assertThat(ready.path("payload").path("agent_id").asLong()).isEqualTo(agentId);
                socket.sendText("{\"type\":\"AGENT_PRESENCE\",\"payload\":{\"status\":\"ONLINE\"}}", true)
                        .get(5, TimeUnit.SECONDS);
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    assertThat(connections.findByAgentId(Long.toString(agentId))).isPresent();
                    assertThat(agents.findById(agentId).orElseThrow().getStatus()).isEqualTo(AgentStatus.ONLINE);
                });

                String nextToken = owner.issueToken(agentId);

                assertThat(listener.closed.get(5, TimeUnit.SECONDS)).isBetween(1000, 4999);
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    assertThat(connections.findByAgentId(Long.toString(agentId))).isEmpty();
                    assertThat(agents.findById(agentId).orElseThrow().getStatus()).isEqualTo(AgentStatus.OFFLINE);
                });
                assertError(machine("GET", "/api/v1/agents/me", null, firstToken),
                        401, "AUTHENTICATION_REQUIRED");
                assertSuccess(machine("GET", "/api/v1/agents/me", null, nextToken), 200);
            } finally {
                socket.abort();
            }
        }
    }

    @Test
    void deletingUnusedAgentInvalidatesTokenAndClosesBrokerConnection() throws Exception {
        try (var owner = new Browser(); var socketClient = HttpClient.newHttpClient()) {
            owner.signupAndLogin();
            long agentId = owner.createAgent(owner.createTeam());
            String token = owner.issueToken(agentId);
            var listener = new BrokerListener();
            var socket = socketClient.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + token)
                    .header("Origin", "http://localhost:3000")
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws/broker"), listener)
                    .get(5, TimeUnit.SECONDS);
            try {
                listener.ready.get(5, TimeUnit.SECONDS);
                assertThat(connections.findByAgentId(Long.toString(agentId))).isPresent();

                assertSuccess(owner.mutate("DELETE", "/api/v1/agents/" + agentId, null), 200);

                assertThat(agents.existsById(agentId)).isFalse();
                assertThat(listener.closed.get(5, TimeUnit.SECONDS)).isBetween(1000, 4999);
                assertThat(connections.findByAgentId(Long.toString(agentId))).isEmpty();
                assertError(machine("GET", "/api/v1/agents/me", null, token), 401, "AUTHENTICATION_REQUIRED");
                assertError(owner.mutate("POST", "/api/v1/agents/" + agentId + "/token", null),
                        404, "RESOURCE_NOT_FOUND");
                assertSuccess(owner.get("/api/v1/auth/users/me"), 200);
            } finally {
                socket.abort();
            }
        }
    }

    private HttpResult machine(String method, String path, Object body, String token) throws Exception {
        try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            var builder = request(method, path, body);
            if (token != null) {
                builder.header("Authorization", "Bearer " + token);
            }
            return result(client.send(builder.build(), HttpResponse.BodyHandlers.ofString()));
        }
    }

    private HttpRequest.Builder request(String method, String path, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Accept", "application/json");
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    }

    private HttpResult result(HttpResponse<String> response) throws Exception {
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        return new HttpResult(response.statusCode(), json.readTree(response.body()));
    }

    private static String hash(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    private void assertSuccess(HttpResult response, int status) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body().path("success").asBoolean()).isTrue();
    }

    private void assertError(HttpResult response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body().path("success").asBoolean()).isFalse();
        assertThat(response.body().path("code").asText()).isEqualTo(code);
    }

    private record HttpResult(int statusCode, JsonNode body) { }

    private record Csrf(String headerName, String token) { }

    private final class Browser implements AutoCloseable {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build();

        private long signupAndLogin() throws Exception {
            String email = "credential-" + UUID.randomUUID() + "@example.com";
            String password = "ValidPassword123!";
            var csrf = csrf();
            assertSuccess(send("POST", "/api/v1/auth/signup", Map.of(
                    "email", email, "password", password, "name", "Credential owner"), csrf), 201);
            var login = send("POST", "/api/v1/auth/login", Map.of("email", email, "password", password), csrf);
            assertSuccess(login, 200);
            return login.body().path("data").path("userId").asLong();
        }

        private long createTeam() throws Exception {
            var response = mutate("POST", "/api/v1/teams", Map.of("name", "Credential team " + UUID.randomUUID()));
            assertSuccess(response, 201);
            return response.body().path("data").path("teamId").asLong();
        }

        private long createAgent(long teamId) throws Exception {
            var response = mutate("POST", "/api/v1/agents/teams/" + teamId, Map.of(
                    "name", "Token agent", "roleDescription", "Agent token integration",
                    "toolType", "CODEX", "projectName", "devlink"));
            assertSuccess(response, 201);
            return response.body().path("data").path("agentId").asLong();
        }

        private String issueToken(long agentId) throws Exception {
            var response = mutate("POST", "/api/v1/agents/" + agentId + "/token", null);
            assertSuccess(response, 201);
            var data = response.body().path("data");
            assertThat(data.path("agentId").asLong()).isEqualTo(agentId);
            assertThat(data.path("token").asText()).isNotBlank();
            assertThat(data.size()).isEqualTo(2);
            return data.path("token").asText();
        }

        private HttpResult get(String path) throws Exception {
            return send("GET", path, null, null);
        }

        private HttpResult getWithAuthorization(String path, String authorization) throws Exception {
            var builder = request("GET", path, null).header("Authorization", authorization);
            return result(client.send(builder.build(), HttpResponse.BodyHandlers.ofString()));
        }

        private String sessionId() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> "JSESSIONID".equals(cookie.getName()))
                    .findFirst().orElseThrow().getValue();
        }

        private HttpResult mutate(String method, String path, Object body) throws Exception {
            return send(method, path, body, csrf());
        }

        private Csrf csrf() throws Exception {
            var response = get("/api/v1/auth/csrf");
            assertSuccess(response, 200);
            return new Csrf(response.body().path("data").path("headerName").asText(),
                    response.body().path("data").path("token").asText());
        }

        private HttpResult send(String method, String path, Object body, Csrf csrf) throws Exception {
            var builder = request(method, path, body);
            if (csrf != null) {
                builder.header(csrf.headerName(), csrf.token());
            }
            return result(client.send(builder.build(), HttpResponse.BodyHandlers.ofString()));
        }

        @Override
        public void close() {
            client.close();
        }
    }

    private static final class BrokerListener implements WebSocket.Listener {
        private final CompletableFuture<String> ready = new CompletableFuture<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder text = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                ready.complete(text.toString());
                text.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            ready.completeExceptionally(error);
            closed.completeExceptionally(error);
        }
    }
}
