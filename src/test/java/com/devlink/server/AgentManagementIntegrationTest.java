package com.devlink.server;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import com.devlink.server.domain.agent.AgentPart;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

class AgentManagementIntegrationTest extends AbstractIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private AgentRepository agents;
    @Autowired private TeamMemberRepository members;
    @MockitoSpyBean private TaskRepository tasks;

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

    @ParameterizedTest
    @EnumSource(TaskParticipant.class)
    void deletingBeforeTaskInsertMakesCreationReturnNotFound(TaskParticipant participant) throws Exception {
        try (var owner = new Browser()) {
            var fixture = owner.prepareTaskRequest();
            long agentId = fixture.agentId(participant);
            var csrf = owner.csrf();
            var beforeInsert = new CountDownLatch(1);
            var resumeInsert = new CountDownLatch(1);
            // 발신·수신 Agent 조회가 끝난 작업을 실제 INSERT 직전에 대기
            doAnswer(invocation -> {
                beforeInsert.countDown();
                if (!resumeInsert.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for the Agent deletion");
                }
                return invocation.callRealMethod();
            }).when(tasks).save(argThat((Task task) -> task != null && fixture.requestKey().equals(task.getRequestKey())));

            try (var executor = Executors.newSingleThreadExecutor()) {
                var creation = executor.submit(() -> createTask(fixture));
                try {
                    assertThat(beforeInsert.await(5, TimeUnit.SECONDS)).as("작업 저장 직전 도달").isTrue();
                    assertSuccess(owner.send("DELETE", "/api/v1/agents/" + agentId, null, csrf), 200);
                } finally {
                    resumeInsert.countDown();
                }
                assertError(creation.get(10, TimeUnit.SECONDS), 404, "RESOURCE_NOT_FOUND");
            }

            assertThat(agents.existsById(agentId)).isFalse();
            assertThat(agents.findByTeamId(fixture.teamId())).hasSize(1);
            assertThat(tasks.findByTeamIdOrderByCreatedAtDesc(fixture.teamId())).isEmpty();
        }
    }

    @ParameterizedTest
    @EnumSource(TaskParticipant.class)
    void deletingAfterTaskCommitReturnsConflictEvenWithStaleTaskCheck(TaskParticipant participant) throws Exception {
        try (var owner = new Browser()) {
            var fixture = owner.prepareTaskRequest();
            long agentId = fixture.agentId(participant);
            var creation = createTask(fixture);
            assertSuccess(creation, 201);
            long taskId = creation.body().path("data").path("taskId").asLong();

            // 작업 커밋 전 사전 조회 결과를 재현하고 삭제는 실제 외래키로 검증
            doReturn(false).when(tasks).existsByRequesterAgentIdOrReceiverAgentId(agentId, agentId);
            assertError(owner.mutate("DELETE", "/api/v1/agents/" + agentId, null), 409, "AGENT_HAS_TASKS");

            assertThat(agents.existsById(fixture.requesterId())).isTrue();
            assertThat(agents.existsById(fixture.receiverId())).isTrue();
            var stored = tasks.findById(taskId).orElseThrow();
            assertThat(stored.getRequesterAgentId()).isEqualTo(fixture.requesterId());
            assertThat(stored.getReceiverAgentId()).isEqualTo(fixture.receiverId());
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

    @Test
    void routingMetadataIsNormalizedAndReturnedBySessionAndAgentReadRoutes() throws Exception {
        try (var owner = new Browser()) {
            owner.signupAndLogin();
            long teamId = owner.createTeam();
            Map<String, Object> body = agentBody();
            body.put("part", "BACKEND");
            body.put("roles", List.of(" api ", "API", "api", " 인증 ", "api"));

            var created = owner.mutate("POST", "/api/v1/agents/teams/" + teamId, body);

            assertSuccess(created, 201);
            JsonNode profile = created.body().path("data");
            long agentId = profile.path("agentId").asLong();
            List<String> roles = List.of("api", "API", "인증");
            assertRoutingMetadata(profile, "BACKEND", roles);
            var stored = agents.findById(agentId).orElseThrow();
            assertThat(stored.getPart()).isEqualTo(AgentPart.BACKEND);
            assertThat(stored.getRoles()).containsExactlyElementsOf(roles);

            var detail = owner.get("/api/v1/agents/" + agentId);
            assertSuccess(detail, 200);
            assertRoutingMetadata(detail.body().path("data"), "BACKEND", roles);

            String listPath = "/api/v1/agents/teams/" + teamId + "?ownedByMe=true";
            var sessionList = owner.get(listPath);
            assertSuccess(sessionList, 200);
            assertThat(sessionList.body().path("data").size()).isEqualTo(1);
            assertRoutingMetadata(sessionList.body().path("data").get(0), "BACKEND", roles);

            var issued = owner.mutate("POST", "/api/v1/agents/" + agentId + "/token", null);
            assertSuccess(issued, 201);
            String token = issued.body().path("data").path("token").asText();

            var agentList = agentRequest("GET", listPath, token, null);
            assertSuccess(agentList, 200);
            assertThat(agentList.body().path("data")).isEqualTo(sessionList.body().path("data"));

            var me = agentRequest("GET", "/api/v1/agents/me", token, null);
            assertSuccess(me, 200);
            assertThat(me.body().path("data").path("agentId").asLong()).isEqualTo(agentId);
            assertRoutingMetadata(me.body().path("data"), "BACKEND", roles);
        }
    }

    @Test
    void omittedAndNullRegistrationMetadataUseBackwardCompatibleDefaults() throws Exception {
        try (var owner = new Browser()) {
            owner.signupAndLogin();
            long teamId = owner.createTeam();
            Map<String, Object> omitted = registrationField("name", "Omitted metadata");
            Map<String, Object> explicitNull = registrationField("name", "Null metadata");
            explicitNull.put("part", null);
            explicitNull.put("roles", null);

            for (Map<String, Object> body : List.of(omitted, explicitNull)) {
                var response = owner.mutate("POST", "/api/v1/agents/teams/" + teamId, body);

                assertSuccess(response, 201);
                JsonNode profile = response.body().path("data");
                assertRoutingMetadata(profile, "OTHER", List.of());
                var stored = agents.findById(profile.path("agentId").asLong()).orElseThrow();
                assertThat(stored.getPart()).isEqualTo(AgentPart.OTHER);
                assertThat(stored.getRoles()).isEmpty();
            }
        }
    }

    @Test
    void patchOmissionAndNullPreserveMetadataWhileEmptyRolesClearOnlyTags() throws Exception {
        try (var owner = new Browser()) {
            owner.signupAndLogin();
            long teamId = owner.createTeam();
            Map<String, Object> body = agentBody();
            body.put("part", "FRONTEND");
            body.put("roles", List.of("React", "UI"));
            var created = owner.mutate("POST", "/api/v1/agents/teams/" + teamId, body);
            assertSuccess(created, 201);
            long agentId = created.body().path("data").path("agentId").asLong();
            String path = "/api/v1/agents/" + agentId;

            var omitted = owner.mutate("PATCH", path, Map.of());
            assertSuccess(omitted, 200);
            assertRoutingMetadata(omitted.body().path("data"), "FRONTEND", List.of("React", "UI"));

            Map<String, Object> nullBody = new LinkedHashMap<>();
            nullBody.put("part", null);
            nullBody.put("roles", null);
            var explicitNull = owner.mutate("PATCH", path, nullBody);
            assertSuccess(explicitNull, 200);
            assertRoutingMetadata(explicitNull.body().path("data"), "FRONTEND", List.of("React", "UI"));

            var cleared = owner.mutate("PATCH", path, Map.of("roles", List.of()));
            assertSuccess(cleared, 200);
            assertRoutingMetadata(cleared.body().path("data"), "FRONTEND", List.of());
            var stored = agents.findById(agentId).orElseThrow();
            assertThat(stored.getPart()).isEqualTo(AgentPart.FRONTEND);
            assertThat(stored.getRoles()).isEmpty();
            assertThat(stored.getRoleDescription()).isEqualTo(body.get("roleDescription"));
        }
    }

    @Test
    void onlyOwnerCanReplaceRoutingMetadataAndUpperLimitsAreAccepted() throws Exception {
        try (var owner = new Browser(); var teammate = new Browser()) {
            owner.signupAndLogin();
            long teamId = owner.createTeam();
            long agentId = owner.createAgent(teamId);
            long teammateId = teammate.signupAndLogin();
            members.saveAndFlush(TeamMember.create(teamId, teammateId, MemberRole.MEMBER));
            String path = "/api/v1/agents/" + agentId;
            Map<String, Object> update = Map.of("part", "INFRA",
                    "roles", List.of(" deploy ", "deploy", "DEPLOY"));

            assertError(teammate.mutate("PATCH", path, update), 403, "ACCESS_DENIED");
            var updated = owner.mutate("PATCH", path, update);
            assertSuccess(updated, 200);
            assertRoutingMetadata(updated.body().path("data"), "INFRA", List.of("deploy", "DEPLOY"));

            List<String> limitRoles = Stream.iterate(0, index -> index + 1).limit(10)
                    .map(index -> "a".repeat(63) + index).toList();
            var limits = owner.mutate("PATCH", path, Map.of("part", "OTHER", "roles", limitRoles));
            assertSuccess(limits, 200);
            assertRoutingMetadata(limits.body().path("data"), "OTHER", limitRoles);
            var stored = agents.findById(agentId).orElseThrow();
            assertThat(stored.getPart()).isEqualTo(AgentPart.OTHER);
            assertThat(stored.getRoles()).containsExactlyElementsOf(limitRoles);
        }
    }

    @ParameterizedTest
    @MethodSource("invalidRoutingMetadataBodies")
    void invalidMetadataPatchDoesNotChangeExistingProfile(Map<String, Object> invalid) throws Exception {
        try (var owner = new Browser()) {
            owner.signupAndLogin();
            long teamId = owner.createTeam();
            Map<String, Object> body = agentBody();
            body.put("part", "BACKEND");
            body.put("roles", List.of("api"));
            var created = owner.mutate("POST", "/api/v1/agents/teams/" + teamId, body);
            assertSuccess(created, 201);
            long agentId = created.body().path("data").path("agentId").asLong();
            String originalName = agents.findById(agentId).orElseThrow().getName();
            Map<String, Object> update = new LinkedHashMap<>(invalid);
            update.put("name", "Should not be saved");

            assertError(owner.mutate("PATCH", "/api/v1/agents/" + agentId, update),
                    400, "VALIDATION_FAILED");

            var stored = agents.findById(agentId).orElseThrow();
            assertThat(stored.getName()).isEqualTo(originalName);
            assertThat(stored.getPart()).isEqualTo(AgentPart.BACKEND);
            assertThat(stored.getRoles()).containsExactly("api");
        }
    }

    private void assertRoutingMetadata(JsonNode profile, String part, List<String> roles) {
        assertThat(profile.path("part").asText()).isEqualTo(part);
        assertThat(profile.path("roles")).isEqualTo(json.valueToTree(roles));
    }

    private HttpResult createTask(TaskRequestFixture fixture) throws Exception {
        return agentRequest("POST", "/api/v1/tasks/" + fixture.teamId(), fixture.token(), Map.of(
                "requestKey", fixture.requestKey(), "receiverAgentId", fixture.receiverId(), "prompt", "Task creation race"));
    }

    private HttpResult agentRequest(String method, String path, String token, Object body) throws Exception {
        try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(20)).header("Accept", "application/json")
                    .header("Authorization", "Bearer " + token);
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new HttpResult(response.statusCode(), json.readTree(response.body()));
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
        return Stream.concat(Stream.of(
                registrationField("name", " "),
                registrationField("name", "A".repeat(101)),
                registrationField("roleDescription", "A".repeat(256)),
                registrationField("toolType", null),
                registrationField("toolType", "UNKNOWN_TOOL"),
                registrationField("projectName", "A".repeat(121))),
                invalidRoutingMetadataBodies().map(metadata -> {
                    Map<String, Object> body = agentBody();
                    body.putAll(metadata);
                    return body;
                }));
    }

    private static Stream<Map<String, Object>> invalidRoutingMetadataBodies() {
        return Stream.of(
                Map.of("part", "UNKNOWN_PART"),
                Map.of("roles", List.of(" ")),
                Map.of("roles", Arrays.asList("api", null)),
                Map.of("roles", List.of("a".repeat(65))),
                Map.of("roles", Collections.nCopies(11, "api")));
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

    private enum TaskParticipant { REQUESTER, RECEIVER }

    private record TaskRequestFixture(long teamId, long requesterId, long receiverId, String token, String requestKey) {
        private long agentId(TaskParticipant participant) {
            return participant == TaskParticipant.REQUESTER ? requesterId : receiverId;
        }
    }

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

        private TaskRequestFixture prepareTaskRequest() throws Exception {
            signupAndLogin();
            long teamId = createTeam();
            long requesterId = createAgent(teamId);
            long receiverId = createAgent(teamId);
            var issued = mutate("POST", "/api/v1/agents/" + requesterId + "/token", null);
            assertSuccess(issued, 201);
            return new TaskRequestFixture(teamId, requesterId, receiverId,
                    issued.body().path("data").path("token").asText(), UUID.randomUUID().toString());
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
