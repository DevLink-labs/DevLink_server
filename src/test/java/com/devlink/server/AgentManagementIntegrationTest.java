package com.devlink.server;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import com.devlink.server.domain.agent.AgentStatus;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.teammember.TeamMember;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TeamMemberRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.assertThat;

class AgentManagementIntegrationTest extends AbstractIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private AgentRepository agents;
    @Autowired private TeamMemberRepository members;
    @Autowired private TaskRepository tasks;

    @Test
    void registeringAgentUsesAuthenticatedOwnerAndReturnsOfflineProfileWithoutCredential() throws Exception {
        try (var browser = new Browser()) {
            long userId = browser.signupAndLogin();
            long teamId = browser.createTeam();
            Map<String, Object> body = agentBody();
            body.put("ownerUserId", userId + 1000);
            body.put("teamId", teamId + 1000);

            var response = browser.mutate("POST", "/api/v1/agents/teams/" + teamId, body);

            assertSuccess(response, 201);
            JsonNode profile = response.body().path("data");
            long agentId = profile.path("agentId").asLong();
            assertThat(agentId).isPositive();
            assertThat(profile.path("teamId").asLong()).isEqualTo(teamId);
            assertThat(profile.path("ownerUserId").asLong()).isEqualTo(userId);
            assertThat(profile.path("status").asText()).isEqualTo("OFFLINE");
            assertThat(profile.path("createdAt").asText()).isNotBlank();
            assertThat(profile.has("connectionToken")).isFalse();
            assertThat(profile.has("connectionTokenHash")).isFalse();
            var stored = agents.findById(agentId).orElseThrow();
            assertThat(stored.getOwnerUserId()).isEqualTo(userId);
            assertThat(stored.getTeamId()).isEqualTo(teamId);
            assertThat(stored.getStatus()).isEqualTo(AgentStatus.OFFLINE);
            assertThat(stored.getConnectionTokenHash()).isNull();
        }
    }

    @Test
    void teamRejectsDuplicateAgentRegistrationAcrossOwners() throws Exception {
        try (var owner = new Browser(); var teammate = new Browser()) {
            owner.signupAndLogin();
            long teamId = owner.createTeam();
            long teammateId = teammate.signupAndLogin();
            members.saveAndFlush(TeamMember.create(teamId, teammateId, MemberRole.MEMBER));
            String path = "/api/v1/agents/teams/" + teamId;

            assertSuccess(owner.mutate("POST", path, agentBody()), 201);
            assertError(teammate.mutate("POST", path, agentBody()), 409, "AGENT_ALREADY_EXISTS");
            assertThat(agents.findByTeamId(teamId)).hasSize(1);
        }
    }

    @Test
    void ownedByMeReturnsOnlyTheAuthenticatedUsersAgents() throws Exception {
        try (var owner = new Browser(); var teammate = new Browser()) {
            owner.signupAndLogin();
            long teamId = owner.createTeam();
            owner.createAgent(teamId);
            long teammateId = teammate.signupAndLogin();
            members.saveAndFlush(TeamMember.create(teamId, teammateId, MemberRole.MEMBER));
            long teammateAgentId = teammate.createAgent(teamId);
            String path = "/api/v1/agents/teams/" + teamId;

            var unfiltered = teammate.get(path);
            assertSuccess(unfiltered, 200);
            assertThat(unfiltered.body().path("data").size()).isEqualTo(2);

            var filtered = teammate.get(path + "?ownedByMe=true");
            assertSuccess(filtered, 200);
            JsonNode profiles = filtered.body().path("data");
            assertThat(profiles.size()).isEqualTo(1);
            assertThat(profiles.get(0).path("agentId").asLong()).isEqualTo(teammateAgentId);
        }
    }

    @Test
    void onlyOwnerCanUpdateAgentProfileAndDuplicateNameIsRejected() throws Exception {
        try (var owner = new Browser(); var teammate = new Browser()) {
            owner.signupAndLogin();
            long teamId = owner.createTeam();
            long agentId = owner.createAgent(teamId);
            long otherAgentId = owner.createAgent(teamId);
            long teammateId = teammate.signupAndLogin();
            members.saveAndFlush(TeamMember.create(teamId, teammateId, MemberRole.MEMBER));
            String path = "/api/v1/agents/" + agentId;
            String otherName = agents.findById(otherAgentId).orElseThrow().getName();

            assertError(teammate.mutate("PATCH", path, Map.of("name", "Reviewer")),
                    403, "ACCESS_DENIED");
            var updated = owner.mutate("PATCH", path,
                    Map.of("name", "Reviewer", "roleDescription", "", "projectName", "Server"));
            assertSuccess(updated, 200);
            assertError(owner.mutate("PATCH", path, Map.of("name", otherName)),
                    409, "AGENT_ALREADY_EXISTS");

            var stored = agents.findById(agentId).orElseThrow();
            assertThat(stored.getName()).isEqualTo("Reviewer");
            assertThat(stored.getRoleDescription()).isNull();
            assertThat(stored.getProjectName()).isEqualTo("Server");
        }
    }

    @Test
    void teamMemberCanReadProfilesButOutsiderCannotReadOrRegister() throws Exception {
        try (var owner = new Browser(); var teammate = new Browser(); var outsider = new Browser()) {
            owner.signupAndLogin();
            long teamId = owner.createTeam();
            long agentId = owner.createAgent(teamId);
            long teammateId = teammate.signupAndLogin();
            members.saveAndFlush(TeamMember.create(teamId, teammateId, MemberRole.MEMBER));
            outsider.signupAndLogin();

            var listing = teammate.get("/api/v1/agents/teams/" + teamId);
            assertSuccess(listing, 200);
            assertThat(listing.body().path("data").size()).isEqualTo(1);
            assertThat(listing.body().path("data").get(0).path("agentId").asLong()).isEqualTo(agentId);
            assertSuccess(teammate.get("/api/v1/agents/" + agentId), 200);
            assertError(outsider.get("/api/v1/agents/teams/" + teamId), 403, "ACCESS_DENIED");
            assertError(outsider.get("/api/v1/agents/" + agentId), 403, "ACCESS_DENIED");
            assertError(outsider.mutate("POST", "/api/v1/agents/teams/" + teamId, agentBody()),
                    403, "ACCESS_DENIED");
        }
    }

    @ParameterizedTest
    @MethodSource("invalidAgentBodies")
    void invalidRegistrationDoesNotCreateAgent(Map<String, Object> body) throws Exception {
        try (var browser = new Browser()) {
            browser.signupAndLogin();
            long teamId = browser.createTeam();
            long count = agents.count();

            assertError(browser.mutate("POST", "/api/v1/agents/teams/" + teamId, body),
                    400, "VALIDATION_FAILED");

            assertThat(agents.count()).isEqualTo(count);
        }
    }

    @Test
    void anonymousCreationRequiresLoginAndAuthenticatedMutationRequiresCsrf() throws Exception {
        try (var browser = new Browser()) {
            var anonymousCsrf = browser.csrf();
            assertError(browser.send("POST", "/api/v1/agents/teams/1", agentBody(), anonymousCsrf),
                    401, "AUTHENTICATION_REQUIRED");
            browser.signupAndLogin();
            long teamId = browser.createTeam();
            assertError(browser.send("POST", "/api/v1/agents/teams/" + teamId, agentBody(), null),
                    403, "ACCESS_DENIED");
        }
    }

    @Test
    void ownerCanDeleteUnusedAgentAndItDisappearsFromQueries() throws Exception {
        try (var owner = new Browser()) {
            owner.signupAndLogin();
            long teamId = owner.createTeam();
            long agentId = owner.createAgent(teamId);

            var deleted = owner.mutate("DELETE", "/api/v1/agents/" + agentId, null);

            assertSuccess(deleted, 200);
            assertThat(deleted.body().path("data").isNull()).isTrue();
            assertThat(agents.existsById(agentId)).isFalse();
            assertThat(owner.get("/api/v1/agents/teams/" + teamId).body().path("data").size()).isZero();
            assertError(owner.get("/api/v1/agents/" + agentId), 404, "RESOURCE_NOT_FOUND");
            assertError(owner.mutate("DELETE", "/api/v1/agents/" + agentId, null), 404, "RESOURCE_NOT_FOUND");
        }
    }

    @Test
    void teamOwnerAndMemberCannotDeleteEachOthersAgents() throws Exception {
        try (var owner = new Browser(); var teammate = new Browser(); var outsider = new Browser()) {
            owner.signupAndLogin();
            long teamId = owner.createTeam();
            long ownerAgentId = owner.createAgent(teamId);
            long teammateId = teammate.signupAndLogin();
            members.saveAndFlush(TeamMember.create(teamId, teammateId, MemberRole.MEMBER));
            long teammateAgentId = teammate.createAgent(teamId);
            outsider.signupAndLogin();

            assertError(teammate.mutate("DELETE", "/api/v1/agents/" + ownerAgentId, null), 403, "ACCESS_DENIED");
            assertError(owner.mutate("DELETE", "/api/v1/agents/" + teammateAgentId, null), 403, "ACCESS_DENIED");
            assertError(outsider.mutate("DELETE", "/api/v1/agents/" + ownerAgentId, null), 403, "ACCESS_DENIED");
            assertThat(agents.existsById(ownerAgentId)).isTrue();
            assertThat(agents.existsById(teammateAgentId)).isTrue();
        }
    }

    @Test
    void agentsReferencedByCompletedTasksCannotBeDeleted() throws Exception {
        try (var owner = new Browser()) {
            long userId = owner.signupAndLogin();
            long teamId = owner.createTeam();
            long requesterId = owner.createAgent(teamId);
            long receiverId = owner.createAgent(teamId);
            Task task = Task.create(UUID.randomUUID().toString(), teamId, requesterId, userId,
                    receiverId, "Preserve task history");
            task.accept(receiverId);
            task.complete(receiverId, "Completed");
            tasks.saveAndFlush(task);

            assertError(owner.mutate("DELETE", "/api/v1/agents/" + requesterId, null), 409, "AGENT_HAS_TASKS");
            assertError(owner.mutate("DELETE", "/api/v1/agents/" + receiverId, null), 409, "AGENT_HAS_TASKS");
            assertThat(agents.existsById(requesterId)).isTrue();
            assertThat(agents.existsById(receiverId)).isTrue();
        }
    }

    @Test
    void deletingAgentRequiresUserSessionCsrfAndValidId() throws Exception {
        try (var owner = new Browser(); var anonymous = new Browser()) {
            owner.signupAndLogin();
            long agentId = owner.createAgent(owner.createTeam());
            String path = "/api/v1/agents/" + agentId;

            assertError(anonymous.send("DELETE", path, null, anonymous.csrf()), 401, "AUTHENTICATION_REQUIRED");
            assertError(owner.send("DELETE", path, null, null), 403, "ACCESS_DENIED");
            assertError(owner.mutate("DELETE", "/api/v1/agents/0", null), 400, "VALIDATION_FAILED");
            assertError(owner.mutate("DELETE", "/api/v1/agents/invalid", null), 400, "VALIDATION_FAILED");
            assertThat(agents.existsById(agentId)).isTrue();
        }
    }

    private static Map<String, Object> agentBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "My agent");
        body.put("roleDescription", "Backend development");
        body.put("toolType", "CODEX");
        body.put("projectName", "devlink");
        return body;
    }

    private static Map<String, Object> registrationField(String name, Object value) {
        Map<String, Object> body = agentBody();
        body.put(name, value);
        return body;
    }

    private static Stream<Map<String, Object>> invalidAgentBodies() {
        return Stream.of(
                registrationField("name", " "),
                registrationField("name", "A".repeat(101)),
                registrationField("roleDescription", "A".repeat(256)),
                registrationField("toolType", null),
                registrationField("toolType", "UNKNOWN_TOOL"),
                registrationField("projectName", "A".repeat(121)));
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
        private final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build();

        private long signupAndLogin() throws Exception {
            String email = "agent-" + UUID.randomUUID() + "@example.com";
            String password = "ValidPassword123!";
            var csrf = csrf();
            assertSuccess(send("POST", "/api/v1/auth/signup", Map.of(
                    "email", email, "password", password, "name", "Agent owner"), csrf), 201);
            var login = send("POST", "/api/v1/auth/login", Map.of("email", email, "password", password), csrf);
            assertSuccess(login, 200);
            return login.body().path("data").path("userId").asLong();
        }

        private long createTeam() throws Exception {
            var response = mutate("POST", "/api/v1/teams", Map.of("name", "Agent team " + UUID.randomUUID()));
            assertSuccess(response, 201);
            return response.body().path("data").path("teamId").asLong();
        }

        private long createAgent(long teamId) throws Exception {
            var response = mutate("POST", "/api/v1/agents/teams/" + teamId,
                    registrationField("name", "Agent " + UUID.randomUUID()));
            assertSuccess(response, 201);
            return response.body().path("data").path("agentId").asLong();
        }

        private HttpResult get(String path) throws Exception {
            return send("GET", path, null, null);
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
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(10)).header("Accept", "application/json");
            if (csrf != null) {
                request.header(csrf.headerName(), csrf.token());
            }
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new HttpResult(response.statusCode(), json.readTree(response.body()));
        }

        @Override
        public void close() {
            client.close();
        }
    }
}
