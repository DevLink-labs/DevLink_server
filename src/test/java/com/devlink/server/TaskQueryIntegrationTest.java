package com.devlink.server;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import com.devlink.server.common.AgentTokenHash;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.domain.taskfile.ChangeType;
import com.devlink.server.domain.taskfile.TaskFile;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.teammember.TeamMember;
import com.devlink.server.domain.user.User;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TaskFileRepository;
import com.devlink.server.repository.TaskEventRepository;
import com.devlink.server.repository.TeamMemberRepository;
import com.devlink.server.repository.TeamRepository;
import com.devlink.server.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

class TaskQueryIntegrationTest extends AbstractIntegrationTest {
    private static final String TEST_PASSWORD = "Query-test-password-123!";
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 1, 1, 9, 0);
    private static final SecureRandom RANDOM = new SecureRandom();

    @LocalServerPort private int port;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private TeamMemberRepository members;
    @Autowired private AgentRepository agents;
    @Autowired private TaskRepository tasks;
    @Autowired private TaskFileRepository taskFiles;
    @Autowired private TaskEventRepository taskEvents;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    private User owner;
    private User teammate;
    private User outsider;
    private Team team;
    private Team otherTeam;
    private TestAgent callerAgent;
    private TestAgent sameOwnerAgent;
    private TestAgent teammateAgent;
    private TestAgent emptyAgent;
    private TestAgent otherTeamAgent;
    private Map<String, Long> taskIds;
    private int taskSequence;

    @Test
    void webHistoryFiltersBeforePaginationAndKeepsCursorStableAfterNewEvents() throws Exception {
        Long taskId = taskIds.get("sentInProgress");
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            createEvent(taskId, TaskEventType.ACK_PENDING, null, BASE_TIME);
            createEvent(taskId, TaskEventType.CREATED, null, BASE_TIME.plusSeconds(9));
            createEvent(taskId, TaskEventType.ACK_RECEIVED, null, BASE_TIME);
            createEvent(taskId, TaskEventType.STATUS_CHANGED, "작업 수락", BASE_TIME.plusSeconds(7));
            createEvent(taskId, TaskEventType.DELIVERY_UNKNOWN, null, BASE_TIME);
            createEvent(taskId, TaskEventType.PROGRESS, "진행 기록", BASE_TIME.plusSeconds(5));
            createEvent(taskId, TaskEventType.DELIVERY_NOT_SENT, null, BASE_TIME);
            TaskEvent question = createConversationEvent(taskId, TaskEventType.QUESTION,
                    teammateAgent.id(), null, BASE_TIME.plusSeconds(3));
            createEvent(taskId, TaskEventType.NOTIFY_FAILED, null, BASE_TIME);
            createConversationEvent(taskId, TaskEventType.ANSWER, callerAgent.id(), question.getId(), BASE_TIME);
            createEvent(taskId, TaskEventType.ACK_RECEIVED, null, BASE_TIME);
            createEvent(taskIds.get("receivedCompleted"), TaskEventType.PROGRESS, "다른 작업", BASE_TIME);
            createEvent(taskIds.get("otherTeamPending"), TaskEventType.PROGRESS, "다른 팀", BASE_TIME);
        });
        try (HttpClient client = loggedInClient(owner)) {
            JsonNode first = assertSuccess(webHistoryGet(client, team.getId(), taskId, "?limit=2"));
            assertWebHistoryPage(first, taskId, "8", true, "8", "10");
            new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                    createEvent(taskId, TaskEventType.PROGRESS, "새 기록", BASE_TIME.plusMinutes(1)));
            List<Map<String, Object>> before = jdbc.queryForList(
                    "select * from task_events where task_id = ? order by sequence", taskId);

            JsonNode second = assertSuccess(webHistoryGet(client, team.getId(), taskId,
                    "?limit=2&beforeSequence=" + first.path("nextBeforeSequence").asText()));
            assertWebHistoryPage(second, taskId, "4", true, "4", "6");
            JsonNode last = assertSuccess(webHistoryGet(client, team.getId(), taskId,
                    "?limit=2&beforeSequence=" + second.path("nextBeforeSequence").asText()));
            assertWebHistoryPage(last, taskId, null, false, "2");
            assertThat(last.path("items").get(0).path("message").isNull()).isTrue();
            assertThat(last.path("items").get(0).path("authorAgent").isNull()).isTrue();
            assertWebHistoryPage(assertSuccess(webHistoryGet(client, team.getId(), taskId,
                    "?limit=2&beforeSequence=2")), taskId, null, false);
            assertWebHistoryPage(assertSuccess(webHistoryGet(client, team.getId(), taskId,
                    "?limit=2&beforeSequence=1")), taskId, null, false);
            // 이벤트가 세 개보다 많이 남아 있어도 표시 대상이 두 개면 다음 페이지는 없다.
            assertWebHistoryPage(assertSuccess(webHistoryGet(client, team.getId(), taskId,
                    "?limit=2&beforeSequence=6")), taskId, null, false, "2", "4");
            assertThat(jdbc.queryForList("select * from task_events where task_id = ? order by sequence", taskId))
                    .isEqualTo(before);
            JsonNode common = assertSuccess(get(eventsPath(taskId) + "?limit=100", callerAgent.token()));
            assertThat(common.path("items").size()).isEqualTo(12);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sentPending", "sentInProgress", "sentCompleted", "sentFailed", "sentRejected"})
    void webHistoryReturnsCurrentResultAndFilesWithoutEventsForEveryStatus(String label) throws Exception {
        Long taskId = taskIds.get(label);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            for (ChangeType type : ChangeType.values()) {
                taskFiles.saveAndFlush(TaskFile.of(taskId, "src/" + type + ".java", type,
                        type == ChangeType.DELETED ? null : "- old\n+ new"));
            }
            taskFiles.saveAndFlush(TaskFile.of(taskIds.get("otherTeamPending"), "other.txt", ChangeType.ADDED, null));
        });
        Map<String, Object> taskBefore = jdbc.queryForMap("select * from tasks where id = ?", taskId);
        List<Map<String, Object>> filesBefore = jdbc.queryForList("select * from task_files where task_id = ?", taskId);
        try (HttpClient client = loggedInClient(teammate)) {
            JsonNode data = assertSuccess(webHistoryGet(client, team.getId(), taskId, ""));
            assertWebHistoryPage(data, taskId, null, false);
            assertThat(data.path("prompt").asText()).isEqualTo("목록 조회 테스트: " + label);
            JsonNode common = assertSuccess(get(detailPath(taskId), callerAgent.token()));
            for (String field : List.of("prompt", "resultText", "failureReason", "rejectionReason",
                    "partiallyModified", "taskFiles")) {
                assertThat(data.get(field)).as(field).isEqualTo(common.get(field));
            }
            assertThat(data.path("taskFiles").size()).isEqualTo(ChangeType.values().length);
            assertThat(assertSuccess(webHistoryGet(client, team.getId(), taskId, "?beforeSequence=1"))).isEqualTo(data);
            assertThat(jdbc.queryForMap("select * from tasks where id = ?", taskId)).isEqualTo(taskBefore);
            assertThat(jdbc.queryForList("select * from task_files where task_id = ?", taskId)).isEqualTo(filesBefore);
            assertThat(taskEvents.findByTaskIdOrderBySequenceAsc(taskId)).isEmpty();
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"default|50", "1|1", "100|100"})
    void webHistoryAppliesDefaultAndLimitBoundariesToVisibleEvents(String limit, int expectedSize) throws Exception {
        Long taskId = taskIds.get("sentCompleted");
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            for (int i = 1; i <= 101; i++) {
                createEvent(taskId, TaskEventType.PROGRESS, "진행 " + i, BASE_TIME);
            }
            createEvent(taskId, TaskEventType.ACK_RECEIVED, null, BASE_TIME);
        });
        try (HttpClient client = loggedInClient(owner)) {
            String query = "default".equals(limit) ? "" : "?limit=" + limit;
            JsonNode data = assertSuccess(webHistoryGet(client, team.getId(), taskId, query));
            assertThat(data.path("items").size()).isEqualTo(expectedSize);
            assertNullableText(data.path("nextBeforeSequence"), Integer.toString(102 - expectedSize));
            assertThat(data.path("hasNext").asBoolean()).isTrue();
            for (int i = 0; i < expectedSize; i++) {
                JsonNode item = data.path("items").get(i);
                int sequence = 102 - expectedSize + i;
                assertDetailEvent(item, TaskEventType.PROGRESS, "진행 " + sequence, BASE_TIME);
                assertThat(item.path("sequence").asText()).isEqualTo(Integer.toString(sequence));
            }
        }
    }

    @Test
    void webHistoryPreservesAuthorsQuestionReferencesAndRawContentAcrossPages() throws Exception {
        User viewer = new TransactionTemplate(transactionManager).execute(tx -> {
            User user = createUser("이력 조회 전용 팀원", passwordEncoder.encode(TEST_PASSWORD));
            members.save(TeamMember.create(team.getId(), user.getId(), MemberRole.MEMBER));
            return user;
        });
        Long taskId = taskIds.get("sentCompleted");
        String body = "첫 줄\n```java\n  return true;\n```\n<script>alert('text')</script> 😀";
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            TaskEvent question = createConversationEvent(taskId, TaskEventType.QUESTION, teammateAgent.id(), null, BASE_TIME);
            TaskEvent answer = createConversationEvent(taskId, TaskEventType.ANSWER, callerAgent.id(), question.getId(), BASE_TIME);
            createConversationEvent(taskId, TaskEventType.ANSWER, teammateAgent.id(), question.getId(), BASE_TIME);
            jdbc.update("update task_events set content = ? where id = ?", body, answer.getId());
            jdbc.update("update tasks set prompt = ? where id = ?", body, taskId);
        });
        try (HttpClient client = loggedInClient(viewer)) {
            JsonNode first = assertSuccess(webHistoryGet(client, team.getId(), taskId, "?limit=2"));
            assertWebHistoryPage(first, taskId, "2", true, "2", "3");
            JsonNode detail = assertSuccess(get(detailPath(taskId), callerAgent.token()));
            assertThat(first.path("items").get(0)).isEqualTo(detail.path("events").get(1));
            assertThat(first.path("items").get(1)).isEqualTo(detail.path("events").get(2));
            assertDetailAgent(first.path("items").get(0).path("authorAgent"), callerAgent);
            assertDetailAgent(first.path("items").get(1).path("authorAgent"), teammateAgent);
            assertThat(first.path("items").get(0).path("message").asText()).isEqualTo(body);
            assertThat(first.path("prompt").asText()).isEqualTo(body);
            JsonNode last = assertSuccess(webHistoryGet(client, team.getId(), taskId, "?beforeSequence=2&limit=2"));
            assertWebHistoryPage(last, taskId, null, false, "1");
            for (JsonNode item : first.path("items")) {
                assertNullableText(item.path("questionEventId"), last.path("items").get(0).path("eventId").asText());
            }
            for (String field : List.of("prompt", "resultText", "failureReason", "rejectionReason", "partiallyModified", "taskFiles")) {
                assertThat(last.get(field)).isEqualTo(first.get(field));
            }
            assertThat(assertSuccess(webHistoryGet(client, team.getId(), taskId, "?limit=2"))).isEqualTo(first);
            assertCurrentUser(client, viewer.getId());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void webHistoryReturnsEmptyPageWhenNoVisibleEventsExist(boolean internalOnly) throws Exception {
        Long taskId = taskIds.get("sentPending");
        if (internalOnly) {
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                for (TaskEventType type : List.of(TaskEventType.ACK_PENDING, TaskEventType.ACK_RECEIVED,
                        TaskEventType.DELIVERY_UNKNOWN, TaskEventType.DELIVERY_NOT_SENT, TaskEventType.NOTIFY_FAILED)) {
                    createEvent(taskId, type, null, BASE_TIME);
                }
            });
        }
        try (HttpClient client = loggedInClient(owner)) {
            JsonNode data = assertSuccess(webHistoryGet(client, team.getId(), taskId, ""));
            assertWebHistoryPage(data, taskId, null, false);
            assertThat(data.path("prompt").asText()).isEqualTo("목록 조회 테스트: sentPending");
            assertEmptyArray(data.path("taskFiles"));
        }
    }

    @Test
    void webHistoryAcceptsMissingSequenceBoundaryWithoutRenumbering() throws Exception {
        Long taskId = taskIds.get("sentInProgress");
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            for (int i = 0; i < 3; i++) createEvent(taskId, TaskEventType.PROGRESS, "진행 기록", BASE_TIME);
            jdbc.update("delete from task_events where task_id = ? and sequence = 2", taskId);
        });
        try (HttpClient client = loggedInClient(owner)) {
            assertWebHistoryPage(assertSuccess(webHistoryGet(client, team.getId(), taskId,
                    "?beforeSequence=2")), taskId, null, false, "1");
            assertWebHistoryPage(assertSuccess(webHistoryGet(client, team.getId(), taskId,
                    "?beforeSequence=9223372036854775807")), taskId, null, false, "1", "3");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"beforeSequence=0", "beforeSequence=-1", "beforeSequence=01",
            "beforeSequence=abc", "beforeSequence=1.5", "beforeSequence=",
            "beforeSequence=9223372036854775808", "beforeSequence=99999999999999999999",
            "limit=0", "limit=-1", "limit=101", "limit=abc", "limit=1.5", "limit=2147483648"})
    void webHistoryRejectsInvalidQueryParameters(String query) throws Exception {
        try (HttpClient client = loggedInClient(owner)) {
            assertError(webHistoryGet(client, team.getId(), taskIds.get("sentCompleted"), "?" + query),
                    400, "VALIDATION_FAILED");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void webHistoryRejectsInvalidPathIds(String invalidId) throws Exception {
        try (HttpClient client = loggedInClient(owner)) {
            for (String path : List.of("/api/v1/web/teams/" + invalidId + "/tasks/" + taskIds.get("sentCompleted") + "/events",
                    webListPath(team.getId()) + "/" + invalidId + "/events")) {
                assertError(client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString()),
                        400, "VALIDATION_FAILED");
            }
        }
    }

    @Test
    void webHistoryRequiresUserSessionInsteadOfAgentToken() throws Exception {
        String path = webHistoryPath(team.getId(), taskIds.get("sentCompleted"));
        assertError(get(path, null), 401, "AUTHENTICATION_REQUIRED");
        assertError(get(path, callerAgent.token()), 401, "AUTHENTICATION_REQUIRED");
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertError(client.send(request(path).header("Cookie", "JSESSIONID=invalid-session").GET().build(),
                    HttpResponse.BodyHandlers.ofString()), 401, "AUTHENTICATION_REQUIRED");
        }
    }

    @Test
    void webHistoryChecksMembershipAndTaskTeamEvenWithoutEvents() throws Exception {
        try (HttpClient client = loggedInClient(outsider)) {
            assertError(client.send(request(webHistoryPath(team.getId(), taskIds.get("sentCompleted")))
                    .header("Authorization", "Bearer " + callerAgent.token()).GET().build(),
                    HttpResponse.BodyHandlers.ofString()), 403, "ACCESS_DENIED");
            assertCurrentUser(client, outsider.getId());
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                members.save(TeamMember.create(otherTeam.getId(), owner.getId(), MemberRole.MEMBER)));
        try (HttpClient client = loggedInClient(owner)) {
            assertError(webHistoryGet(client, team.getId(), taskIds.get("otherTeamPending"), ""), 403, "ACCESS_DENIED");
            assertError(webHistoryGet(client, otherTeam.getId(), taskIds.get("sentCompleted"), ""), 403, "ACCESS_DENIED");
            assertWebHistoryPage(assertSuccess(webHistoryGet(client, otherTeam.getId(), taskIds.get("otherTeamPending"), "")),
                    taskIds.get("otherTeamPending"), null, false);
        }
    }

    @Test
    void webHistoryReturnsNotFoundForMissingTeamOrTask() throws Exception {
        try (HttpClient client = loggedInClient(owner)) {
            assertError(webHistoryGet(client, Long.MAX_VALUE, taskIds.get("sentCompleted"), ""), 404, "RESOURCE_NOT_FOUND");
            assertError(webHistoryGet(client, team.getId(), Long.MAX_VALUE, ""), 404, "RESOURCE_NOT_FOUND");
        }
    }

    @Test
    void webHistoryRechecksMembershipAfterTeamDeparture() throws Exception {
        try (HttpClient client = loggedInClient(owner)) {
            assertSuccess(webHistoryGet(client, team.getId(), taskIds.get("sentCompleted"), ""));
            jdbc.update("delete from team_members where team_id = ? and user_id = ?", team.getId(), owner.getId());
            assertError(webHistoryGet(client, team.getId(), taskIds.get("sentCompleted"), ""), 403, "ACCESS_DENIED");
        }
    }

    private String webHistoryPath(Long teamId, Long taskId) {
        return webDetailPath(teamId, taskId) + "/events";
    }

    private HttpResponse<String> webHistoryGet(HttpClient client, Long teamId, Long taskId, String query) throws Exception {
        return client.send(request(webHistoryPath(teamId, taskId) + query).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertWebHistoryPage(JsonNode data, Long taskId, String cursor, boolean hasNext, String... sequences) {
        List<String> fields = new ArrayList<>();
        data.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("taskId", "prompt", "resultText", "failureReason",
                "rejectionReason", "partiallyModified", "taskFiles", "items", "nextBeforeSequence", "hasNext");
        assertThat(data.path("taskId").asLong()).isEqualTo(taskId);
        assertThat(data.path("items").isArray()).isTrue();
        List<String> actual = new ArrayList<>();
        data.path("items").forEach(item -> {
            assertThat(item.path("eventId").isTextual()).isTrue();
            assertThat(item.path("sequence").isTextual()).isTrue();
            actual.add(item.path("sequence").asText());
        });
        assertThat(actual).containsExactly(sequences);
        assertNullableText(data.path("nextBeforeSequence"), cursor);
        assertThat(data.path("hasNext").isBoolean()).isTrue();
        assertThat(data.path("hasNext").asBoolean()).isEqualTo(hasNext);
    }

    @ParameterizedTest
    @CsvSource({"sentPending,PENDING,0,false,false", "sentInProgress,IN_PROGRESS,1,true,false",
            "sentCompleted,COMPLETED,2,true,true", "sentFailed,FAILED,3,true,true",
            "sentRejected,REJECTED,4,false,true"})
    void webDetailReturnsOnlyHeaderFieldsForEveryStatus(String label, String status, int minute,
            boolean hasStarted, boolean hasCompleted) throws Exception {
        Long taskId = taskIds.get(label);
        try (HttpClient client = loggedInClient(teammate)) {
            JsonNode data = assertSuccess(webDetailGet(client, team.getId(), taskId));
            List<String> fields = new ArrayList<>();
            data.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("taskId", "teamId", "teamName", "taskSummary",
                    "requesterAgent", "receiverAgent", "status", "createdAt", "startedAt",
                    "completedAt", "lastActivityAt");
            assertThat(data.path("taskId").asLong()).isEqualTo(taskId);
            assertThat(data.path("teamId").asLong()).isEqualTo(team.getId());
            assertThat(data.path("teamName").asText()).isEqualTo(team.getName());
            assertNullableText(data.path("taskSummary"), null);
            assertDetailAgent(data.path("requesterAgent"), callerAgent);
            assertDetailAgent(data.path("receiverAgent"), teammateAgent);
            assertThat(data.path("status").asText()).isEqualTo(status);
            LocalDateTime createdAt = BASE_TIME.plusMinutes(minute);
            assertTimestamp(data.path("createdAt"), createdAt);
            assertTimestamp(data.path("startedAt"), hasStarted ? createdAt.plusSeconds(10) : null);
            assertTimestamp(data.path("completedAt"), hasCompleted ? createdAt.plusSeconds(20) : null);
            assertTimestamp(data.path("lastActivityAt"), createdAt);
        }
    }

    @Test
    void webDetailAllowsMemberWithoutAgentAndReturnsStoredSummaryAndCurrentNames() throws Exception {
        User viewer = new TransactionTemplate(transactionManager).execute(tx -> {
            User user = createUser("상세 조회 전용 팀원", passwordEncoder.encode(TEST_PASSWORD));
            members.save(TeamMember.create(team.getId(), user.getId(), MemberRole.MEMBER));
            return user;
        });
        Long taskId = taskIds.get("sentCompleted");
        String summary = "게시글 응답 지연 분석\n  검증 항목 😀";
        jdbc.update("update tasks set task_summary = ? where id = ?", summary, taskId);
        jdbc.update("update teams set name = ? where id = ?", "변경된 팀 이름", team.getId());
        jdbc.update("update agents set name = ? where id = ?", "변경된 요청 Agent", callerAgent.id());
        jdbc.update("update agents set name = ? where id = ?", "변경된 수신 Agent", teammateAgent.id());
        try (HttpClient client = loggedInClient(viewer)) {
            JsonNode data = assertSuccess(webDetailGet(client, team.getId(), taskId));
            assertNullableText(data.path("taskSummary"), summary);
            assertThat(data.path("teamName").asText()).isEqualTo("변경된 팀 이름");
            assertThat(data.path("requesterAgent").path("name").asText()).isEqualTo("변경된 요청 Agent");
            assertThat(data.path("receiverAgent").path("name").asText()).isEqualTo("변경된 수신 Agent");
            JsonNode common = assertSuccess(get(detailPath(taskId), callerAgent.token()));
            data.fieldNames().forEachRemaining(field -> assertThat(data.get(field)).isEqualTo(common.get(field)));
            assertThat(common.path("resultText").asText()).isEqualTo("테스트 작업 완료");
            assertThat(common.path("prompt").asText()).isEqualTo("목록 조회 테스트: sentCompleted");
            assertCurrentUser(client, viewer.getId());
        }
    }

    @Test
    void webDetailSupportsSameRequesterAndReceiverAgent() throws Exception {
        try (HttpClient client = loggedInClient(owner)) {
            JsonNode data = assertSuccess(webDetailGet(client, team.getId(), taskIds.get("selfPending")));
            assertDetailAgent(data.path("requesterAgent"), callerAgent);
            assertDetailAgent(data.path("receiverAgent"), callerAgent);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void webDetailRejectsInvalidPathIds(String invalidId) throws Exception {
        try (HttpClient client = loggedInClient(owner)) {
            for (String path : List.of("/api/v1/web/teams/" + invalidId + "/tasks/" + taskIds.get("sentCompleted"),
                    webListPath(team.getId()) + "/" + invalidId)) {
                assertError(client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString()),
                        400, "VALIDATION_FAILED");
            }
        }
    }

    @Test
    void webDetailRequiresUserSessionInsteadOfAgentToken() throws Exception {
        String path = webDetailPath(team.getId(), taskIds.get("sentCompleted"));
        assertError(get(path, null), 401, "AUTHENTICATION_REQUIRED");
        assertError(get(path, callerAgent.token()), 401, "AUTHENTICATION_REQUIRED");
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertError(client.send(request(path).header("Cookie", "JSESSIONID=invalid-session").GET().build(),
                    HttpResponse.BodyHandlers.ofString()), 401, "AUTHENTICATION_REQUIRED");
        }
    }

    @Test
    void webDetailRejectsNonMemberEvenWithParticipatingAgentToken() throws Exception {
        try (HttpClient client = loggedInClient(outsider)) {
            assertError(client.send(request(webDetailPath(team.getId(), taskIds.get("sentCompleted")))
                    .header("Authorization", "Bearer " + callerAgent.token()).GET().build(),
                    HttpResponse.BodyHandlers.ofString()), 403, "ACCESS_DENIED");
            assertCurrentUser(client, outsider.getId());
        }
    }

    @Test
    void webDetailRejectsTeamMismatchEvenWhenUserBelongsToBothTeams() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                members.save(TeamMember.create(otherTeam.getId(), owner.getId(), MemberRole.MEMBER)));
        try (HttpClient client = loggedInClient(owner)) {
            assertError(webDetailGet(client, team.getId(), taskIds.get("otherTeamPending")), 403, "ACCESS_DENIED");
            assertError(webDetailGet(client, otherTeam.getId(), taskIds.get("sentCompleted")), 403, "ACCESS_DENIED");
            JsonNode allowed = assertSuccess(webDetailGet(client, otherTeam.getId(), taskIds.get("otherTeamPending")));
            assertThat(allowed.path("teamId").asLong()).isEqualTo(otherTeam.getId());
        }
    }

    @Test
    void webDetailReturnsNotFoundForMissingTeamOrTask() throws Exception {
        try (HttpClient client = loggedInClient(owner)) {
            assertError(webDetailGet(client, Long.MAX_VALUE, taskIds.get("sentCompleted")), 404, "RESOURCE_NOT_FOUND");
            assertError(webDetailGet(client, team.getId(), Long.MAX_VALUE), 404, "RESOURCE_NOT_FOUND");
        }
    }

    @Test
    void webDetailRechecksMembershipAfterTeamDeparture() throws Exception {
        try (HttpClient client = loggedInClient(owner)) {
            assertSuccess(webDetailGet(client, team.getId(), taskIds.get("sentCompleted")));
            jdbc.update("delete from team_members where team_id = ? and user_id = ?", team.getId(), owner.getId());
            assertError(webDetailGet(client, team.getId(), taskIds.get("sentCompleted")), 403, "ACCESS_DENIED");
        }
    }

    @Test
    void webDetailRefreshesActivityWithoutReturningOrChangingHistoryAndFiles() throws Exception {
        Long taskId = taskIds.get("sentInProgress");
        try (HttpClient client = loggedInClient(owner)) {
            JsonNode first = assertSuccess(webDetailGet(client, team.getId(), taskId));
            assertTimestamp(first.path("lastActivityAt"), BASE_TIME.plusMinutes(1));
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                createEvent(taskId, TaskEventType.STATUS_CHANGED, "작업 수락", BASE_TIME.plusMinutes(15));
                createEvent(taskId, TaskEventType.ACK_RECEIVED, null, BASE_TIME.plusHours(3));
                createEvent(taskId, TaskEventType.PROGRESS, "진행 기록", BASE_TIME.plusHours(4));
                taskFiles.saveAndFlush(TaskFile.of(taskId, "src/Example.java", ChangeType.MODIFIED, "- old\n+ new"));
            });
            Map<String, Object> taskBefore = jdbc.queryForMap("select * from tasks where id = ?", taskId);
            List<Map<String, Object>> eventsBefore = jdbc.queryForList(
                    "select * from task_events where task_id = ? order by sequence", taskId);
            List<Map<String, Object>> filesBefore = jdbc.queryForList("select * from task_files where task_id = ?", taskId);

            JsonNode refreshed = assertSuccess(webDetailGet(client, team.getId(), taskId));
            assertTimestamp(refreshed.path("lastActivityAt"), BASE_TIME.plusMinutes(15));
            JsonNode list = assertSuccess(webGet(client, team.getId(), ""));
            assertThat(refreshed.path("lastActivityAt")).isEqualTo(findItem(list, "sentInProgress").path("lastActivityAt"));
            assertThat(refreshed.has("events")).isFalse();
            assertThat(refreshed.has("taskFiles")).isFalse();
            assertThat(assertSuccess(webDetailGet(client, team.getId(), taskId))).isEqualTo(refreshed);
            assertThat(jdbc.queryForMap("select * from tasks where id = ?", taskId)).isEqualTo(taskBefore);
            assertThat(jdbc.queryForList("select * from task_events where task_id = ? order by sequence", taskId))
                    .isEqualTo(eventsBefore);
            assertThat(jdbc.queryForList("select * from task_files where task_id = ?", taskId)).isEqualTo(filesBefore);
            JsonNode common = assertSuccess(get(detailPath(taskId), callerAgent.token()));
            assertThat(common.path("events").size()).isEqualTo(3);
            assertThat(common.path("taskFiles").size()).isEqualTo(1);
        }
    }

    private String webDetailPath(Long teamId, Long taskId) {
        return webListPath(teamId) + "/" + taskId;
    }

    private HttpResponse<String> webDetailGet(HttpClient client, Long teamId, Long taskId) throws Exception {
        return client.send(request(webDetailPath(teamId, taskId)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void webListAllowsTeamMemberWithoutAgentsAndMatchesCommonTeamResponse() throws Exception {
        User viewer = new TransactionTemplate(transactionManager).execute(tx -> {
            User user = createUser("Agent 없는 조회 사용자", passwordEncoder.encode(TEST_PASSWORD));
            members.save(TeamMember.create(team.getId(), user.getId(), MemberRole.MEMBER));
            return user;
        });
        String summary = "작업 요약\n  코드 확인 😀";
        jdbc.update("update tasks set task_summary = ? where id = ?", summary, taskIds.get("sentCompleted"));

        try (HttpClient client = loggedInClient(viewer)) {
            JsonNode data = assertSuccess(webGet(client, team.getId(), ""));
            assertItems(data, "sameOwnerReceived", "sameOwnerSent", "selfPending", "receivedCompleted",
                    "receivedPending", "sentRejected", "sentFailed", "sentCompleted", "sentInProgress", "sentPending");
            assertPage(data, 0, 20, 10, 1, false);
            assertThat(data).isEqualTo(assertSuccess(getTasks(callerAgent.token(), "?scope=team")));
            assertNullableText(findItem(data, "sentCompleted").path("taskSummary"), summary);
            assertNullableText(findItem(data, "sentPending").path("taskSummary"), null);
            for (JsonNode item : data.path("items")) {
                assertThat(item.path("teamId").asLong()).isEqualTo(team.getId());
                assertThat(item.has("summaryPreview")).isFalse();
                for (String excluded : List.of("prompt", "events", "resultText", "taskFiles")) {
                    assertThat(item.has(excluded)).isFalse();
                }
            }
            assertCurrentUser(client, viewer.getId());
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "PENDING|sameOwnerSent,selfPending,receivedPending,sentPending",
            "IN_PROGRESS|sentInProgress",
            "COMPLETED|receivedCompleted,sentCompleted",
            "FAILED|sameOwnerReceived,sentFailed",
            "REJECTED|sentRejected"
    })
    void webListFiltersEveryTaskStatus(String status, String labels) throws Exception {
        try (HttpClient client = loggedInClient(teammate)) {
            JsonNode data = assertSuccess(webGet(client, team.getId(), "?status=" + status));
            String[] expected = labels.split(",");
            assertItems(data, expected);
            assertPage(data, 0, 20, expected.length, 1, false);
            for (JsonNode item : data.path("items")) {
                assertThat(item.path("status").asText()).isEqualTo(status);
            }
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "0|true|sameOwnerReceived,sameOwnerSent,selfPending,receivedCompleted",
            "1|true|receivedPending,sentRejected,sentFailed,sentCompleted",
            "2|false|sentInProgress,sentPending"
    })
    void webListPaginatesWithStableTotals(int page, boolean hasNext, String labels) throws Exception {
        try (HttpClient client = loggedInClient(owner)) {
            JsonNode data = assertSuccess(webGet(client, team.getId(), "?size=4&page=" + page));
            assertItems(data, labels.split(","));
            assertPage(data, page, 4, 10, 3, hasNext);
            JsonNode beyond = assertSuccess(webGet(client, team.getId(), "?size=4&page=3"));
            assertItems(beyond);
            assertPage(beyond, 3, 4, 10, 3, false);
        }
    }

    @Test
    void webListCombinesStatusAndPaginationAndAcceptsSizeBoundaries() throws Exception {
        try (HttpClient client = loggedInClient(owner)) {
            JsonNode filtered = assertSuccess(webGet(client, team.getId(), "?status=FAILED&size=1&page=1"));
            assertItems(filtered, "sentFailed");
            assertPage(filtered, 1, 1, 2, 2, false);
            JsonNode maximum = assertSuccess(webGet(client, team.getId(), "?size=100"));
            assertPage(maximum, 0, 100, 10, 1, false);
        }
    }

    @Test
    void webListSortsEqualCreationTimesByIdInsteadOfRecentActivity() throws Exception {
        jdbc.update("update tasks set created_at = ? where id = ?",
                Timestamp.valueOf(BASE_TIME.plusMinutes(9)), taskIds.get("sentPending"));
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                createEvent(taskIds.get("sentInProgress"), TaskEventType.STATUS_CHANGED,
                        "최근 활동", BASE_TIME.plusHours(1)));
        try (HttpClient client = loggedInClient(owner)) {
            assertItems(assertSuccess(webGet(client, team.getId(), "?size=1&page=0")), "sameOwnerReceived");
            assertItems(assertSuccess(webGet(client, team.getId(), "?size=1&page=1")), "sentPending");
        }
    }

    @Test
    void webListChecksAuthorizationEvenForTeamWithoutTasks() throws Exception {
        Team emptyTeam = new TransactionTemplate(transactionManager).execute(tx -> {
            Team created = teams.save(Team.create("작업 없는 팀", UUID.randomUUID().toString()));
            members.save(TeamMember.create(created.getId(), owner.getId(), MemberRole.OWNER));
            return created;
        });
        try (HttpClient client = loggedInClient(owner)) {
            JsonNode empty = assertSuccess(webGet(client, emptyTeam.getId(), ""));
            assertItems(empty);
            assertPage(empty, 0, 20, 0, 0, false);
        }
        try (HttpClient client = loggedInClient(outsider)) {
            assertError(webGet(client, emptyTeam.getId(), ""), 403, "ACCESS_DENIED");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"status=UNKNOWN", "page=-1", "page=abc", "page=1.5", "page=2147483648",
            "size=0", "size=-1", "size=101", "size=abc", "size=1.5", "size=2147483648"})
    void webListRejectsInvalidQueryParameters(String query) throws Exception {
        try (HttpClient client = loggedInClient(owner)) {
            assertError(webGet(client, team.getId(), "?" + query), 400, "VALIDATION_FAILED");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void webListRejectsInvalidTeamId(String teamId) throws Exception {
        try (HttpClient client = loggedInClient(owner)) {
            assertError(client.send(request("/api/v1/web/teams/" + teamId + "/tasks").GET().build(),
                    HttpResponse.BodyHandlers.ofString()), 400, "VALIDATION_FAILED");
        }
    }

    @Test
    void webListRejectsMissingOrInvalidSessionAndAgentTokenAlone() throws Exception {
        String path = webListPath(team.getId());
        assertError(get(path, null), 401, "AUTHENTICATION_REQUIRED");
        assertError(get(path, callerAgent.token()), 401, "AUTHENTICATION_REQUIRED");
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertError(client.send(request(path).header("Cookie", "JSESSIONID=invalid-session").GET().build(),
                    HttpResponse.BodyHandlers.ofString()), 401, "AUTHENTICATION_REQUIRED");
        }
    }

    @Test
    void webListRejectsOtherTeamAndMissingTeam() throws Exception {
        try (HttpClient client = loggedInClient(outsider)) {
            // Agent 토큰을 함께 보내도 로그인 사용자의 팀 권한을 대신하지 않는다.
            assertError(client.send(request(webListPath(team.getId()))
                    .header("Authorization", "Bearer " + callerAgent.token()).GET().build(),
                    HttpResponse.BodyHandlers.ofString()), 403, "ACCESS_DENIED");
            JsonNode ownTeam = assertSuccess(webGet(client, otherTeam.getId(), ""));
            assertItems(ownTeam, "otherTeamPending");
            assertError(webGet(client, Long.MAX_VALUE, ""), 404, "RESOURCE_NOT_FOUND");
            assertCurrentUser(client, outsider.getId());
        }
    }

    @Test
    void webListRechecksMembershipOnEachRequest() throws Exception {
        try (HttpClient client = loggedInClient(owner)) {
            assertSuccess(webGet(client, team.getId(), ""));
            jdbc.update("delete from team_members where team_id = ? and user_id = ?", team.getId(), owner.getId());
            assertError(webGet(client, team.getId(), ""), 403, "ACCESS_DENIED");
        }
    }

    @Test
    void webListRefreshesStoredActivityWithoutChangingTaskOrHistory() throws Exception {
        Long taskId = taskIds.get("sentPending");
        try (HttpClient client = loggedInClient(owner)) {
            JsonNode first = assertSuccess(webGet(client, team.getId(), ""));
            assertTimestamp(findItem(first, "sentPending").path("lastActivityAt"), BASE_TIME);
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                createEvent(taskId, TaskEventType.STATUS_CHANGED, "작업 수락", BASE_TIME.plusMinutes(15));
                createEvent(taskId, TaskEventType.ACK_RECEIVED, null, BASE_TIME.plusHours(3));
            });
            Map<String, Object> taskBefore = jdbc.queryForMap("select * from tasks where id = ?", taskId);
            List<Map<String, Object>> eventsBefore = jdbc.queryForList(
                    "select * from task_events where task_id = ? order by sequence", taskId);

            JsonNode refreshed = assertSuccess(webGet(client, team.getId(), ""));
            assertTimestamp(findItem(refreshed, "sentPending").path("lastActivityAt"), BASE_TIME.plusMinutes(15));
            assertThat(assertSuccess(webGet(client, team.getId(), ""))).isEqualTo(refreshed);
            assertThat(jdbc.queryForMap("select * from tasks where id = ?", taskId)).isEqualTo(taskBefore);
            assertThat(jdbc.queryForList("select * from task_events where task_id = ? order by sequence", taskId))
                    .isEqualTo(eventsBefore);
        }
    }

    private HttpClient loggedInClient(User user) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        try {
            loginAs(client, user);
            return client;
        } catch (Exception | Error e) {
            client.close();
            throw e;
        }
    }

    private String webListPath(Long teamId) {
        return "/api/v1/web/teams/" + teamId + "/tasks";
    }

    private HttpResponse<String> webGet(HttpClient client, Long teamId, String query) throws Exception {
        return client.send(request(webListPath(teamId) + query).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void rejectsRequestWithoutAgentToken() throws Exception {
        assertError(getTasks(null, ""), 401, "AUTHENTICATION_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer unknown-token", "Basic credentials", "Bearer", "Bearer first second"})
    void rejectsInvalidAuthorizationHeader(String authorization) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var request = request(listPath(team.getId()))
                    .header("Authorization", authorization).GET().build();
            assertError(client.send(request, HttpResponse.BodyHandlers.ofString()),
                    401, "AUTHENTICATION_REQUIRED");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"mine", "team"})
    void returnsStoredTaskSummaryInList(String scope) throws Exception {
        String summary = "게시글 목록 API 응답 지연 분석\n검증 항목 확인 😀";
        jdbc.update("update tasks set task_summary = ? where id = ?",
                summary, taskIds.get("sentCompleted"));

        JsonNode data = assertSuccess(getTasks(callerAgent.token(), "?scope=" + scope));
        JsonNode item = findItem(data, "sentCompleted");

        assertThat(item.has("taskSummary")).isTrue();
        assertThat(item.get("taskSummary").asText()).isEqualTo(summary);
    }

    @Test
    void returnsExplicitNullWhenTaskHasNoSummary() throws Exception {
        JsonNode data = assertSuccess(getTasks(callerAgent.token(),
                "?direction=sent&status=COMPLETED"));
        JsonNode item = findItem(data, "sentCompleted");

        assertThat(item.has("taskSummary")).isTrue();
        assertThat(item.get("taskSummary").isNull()).isTrue();
    }

    @Test
    void returnsStoredTaskSummaryInDetailWithoutReplacingPromptOrResult() throws Exception {
        Long taskId = taskIds.get("sentCompleted");
        String summary = "상세 조회용 작업 요약\n검증 항목 확인 😀";
        jdbc.update("update tasks set task_summary = ? where id = ?", summary, taskId);

        JsonNode data = assertSuccess(get(detailPath(taskId), callerAgent.token()));

        assertThat(data.has("taskSummary")).isTrue();
        assertThat(data.get("taskSummary").asText()).isEqualTo(summary);
        assertThat(data.path("prompt").asText()).isEqualTo("목록 조회 테스트: sentCompleted");
        assertThat(data.path("resultText").asText()).isEqualTo("테스트 작업 완료");
    }

    @Test
    void returnsExplicitNullInDetailWhenTaskHasNoSummary() throws Exception {
        JsonNode data = assertSuccess(get(detailPath(taskIds.get("sentCompleted")), callerAgent.token()));

        assertThat(data.has("taskSummary")).isTrue();
        assertThat(data.get("taskSummary").isNull()).isTrue();
        assertThat(data.path("resultText").asText()).isEqualTo("테스트 작업 완료");
    }

    @Test
    void usesCreationTimeAsLastActivityWhenNoEventsExist() throws Exception {
        JsonNode data = assertSuccess(getTasks(callerAgent.token(), ""));
        JsonNode item = findItem(data, "sentPending");

        assertThat(item.has("lastActivityAt")).isTrue();
        assertTimestamp(item.path("lastActivityAt"), BASE_TIME);

        JsonNode detail = assertSuccess(get(detailPath(taskIds.get("sentPending")), callerAgent.token()));
        assertTimestamp(detail.path("lastActivityAt"), BASE_TIME);
    }

    @Test
    void ignoresDeliveryOnlyEventsWhenChoosingLastActivity() throws Exception {
        Long taskId = taskIds.get("sentPending");
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            for (TaskEventType type : List.of(TaskEventType.ACK_PENDING, TaskEventType.ACK_RECEIVED,
                    TaskEventType.DELIVERY_UNKNOWN, TaskEventType.DELIVERY_NOT_SENT,
                    TaskEventType.NOTIFY_FAILED)) {
                createEvent(taskId, type, null, BASE_TIME.plusHours(3));
            }
        });

        JsonNode data = assertSuccess(getTasks(callerAgent.token(), ""));

        assertTimestamp(findItem(data, "sentPending").path("lastActivityAt"), BASE_TIME);

        JsonNode detail = assertSuccess(get(detailPath(taskId), callerAgent.token()));
        assertTimestamp(detail.path("lastActivityAt"), BASE_TIME);
    }

    @ParameterizedTest
    @ValueSource(strings = {"mine", "team"})
    void selectsLatestActivityPerTaskWithoutUsingDeliveryEvents(String scope) throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            Long sentTaskId = taskIds.get("sentPending");
            createEvent(sentTaskId, TaskEventType.CREATED, null, BASE_TIME.plusSeconds(1));
            createEvent(sentTaskId, TaskEventType.STATUS_CHANGED, "작업 수락", BASE_TIME.plusMinutes(15));
            // 나중에 저장한 과거 기록이 최신 기록을 덮어쓰지 않아야 한다.
            createEvent(sentTaskId, TaskEventType.STATUS_CHANGED, "이전 상태 기록", BASE_TIME.plusMinutes(5));
            createEvent(sentTaskId, TaskEventType.NOTIFY_FAILED, "알림 실패", BASE_TIME.plusHours(4));
            createEvent(taskIds.get("receivedPending"), TaskEventType.STATUS_CHANGED,
                    "다른 작업의 상태 기록", BASE_TIME.plusMinutes(30));
            createEvent(taskIds.get("otherTeamPending"), TaskEventType.STATUS_CHANGED,
                    "다른 팀의 상태 기록", BASE_TIME.plusHours(5));
        });

        JsonNode data = assertSuccess(getTasks(callerAgent.token(), "?scope=" + scope));

        assertTimestamp(findItem(data, "sentPending").path("lastActivityAt"), BASE_TIME.plusMinutes(15));
        assertTimestamp(findItem(data, "receivedPending").path("lastActivityAt"), BASE_TIME.plusMinutes(30));

        JsonNode sentDetail = assertSuccess(get(detailPath(taskIds.get("sentPending")), callerAgent.token()));
        JsonNode receivedDetail = assertSuccess(get(detailPath(taskIds.get("receivedPending")), callerAgent.token()));
        assertTimestamp(sentDetail.path("lastActivityAt"), BASE_TIME.plusMinutes(15));
        assertTimestamp(receivedDetail.path("lastActivityAt"), BASE_TIME.plusMinutes(30));
    }

    @ParameterizedTest
    @CsvSource({
            "Asia/Seoul,QUESTION,2026-01-01T00:20:00",
            "Asia/Seoul,ANSWER,2026-01-01T00:20:00",
            "UTC,QUESTION,2026-01-01T09:20:00",
            "UTC,ANSWER,2026-01-01T09:20:00"
    })
    void convertsConversationTimeBeforeComparingLastActivity(
            String zoneId, TaskEventType type, String utcCreatedAt) throws Exception {
        TimeZone originalZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
            LocalDateTime conversationTime = LocalDateTime.parse(utcCreatedAt);
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                Long taskId = taskIds.get("sentPending");
                createEvent(taskId, TaskEventType.STATUS_CHANGED, "작업 수락", BASE_TIME.plusMinutes(15));
                TaskEvent question = createConversationEvent(taskId, TaskEventType.QUESTION,
                        teammateAgent.id(), null,
                        type == TaskEventType.QUESTION ? conversationTime : conversationTime.minusMinutes(10));
                if (type == TaskEventType.ANSWER) {
                    createConversationEvent(taskId, TaskEventType.ANSWER,
                            callerAgent.id(), question.getId(), conversationTime);
                }
            });

            JsonNode data = assertSuccess(getTasks(callerAgent.token(), ""));

            assertTimestamp(findItem(data, "sentPending").path("lastActivityAt"), BASE_TIME.plusMinutes(20));

            JsonNode detail = assertSuccess(get(detailPath(taskIds.get("sentPending")), callerAgent.token()));
            assertTimestamp(detail.path("lastActivityAt"), BASE_TIME.plusMinutes(20));
            try (HttpClient client = loggedInClient(owner)) {
                JsonNode web = assertSuccess(webGet(client, team.getId(), ""));
                assertTimestamp(findItem(web, "sentPending").path("lastActivityAt"), BASE_TIME.plusMinutes(20));
                JsonNode webDetail = assertSuccess(webDetailGet(client, team.getId(), taskIds.get("sentPending")));
                assertTimestamp(webDetail.path("lastActivityAt"), BASE_TIME.plusMinutes(20));
            }
        } finally {
            TimeZone.setDefault(originalZone);
        }
    }

    @Test
    void usesMineAllAndDefaultPaginationWhenQueryIsOmitted() throws Exception {
        JsonNode data = assertSuccess(getTasks(callerAgent.token(), ""));

        assertItems(data, "selfPending", "receivedCompleted", "receivedPending", "sentRejected",
                "sentFailed", "sentCompleted", "sentInProgress", "sentPending");
        assertPage(data, 0, 20, 8, 1, false);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "all|selfPending,receivedCompleted,receivedPending,sentRejected,sentFailed,sentCompleted,sentInProgress,sentPending",
            "sent|selfPending,sentRejected,sentFailed,sentCompleted,sentInProgress,sentPending",
            "received|selfPending,receivedCompleted,receivedPending"
    })
    void filtersDirectionAndReturnsSelfTaskOnlyOnce(String direction, String labels) throws Exception {
        JsonNode data = assertSuccess(getTasks(callerAgent.token(), "?scope=mine&direction=" + direction));

        String[] expected = labels.split(",");
        assertItems(data, expected);
        assertPage(data, 0, 20, expected.length, 1, false);
    }

    @Test
    void mineScopeDoesNotIncludeAnotherAgentOwnedBySameUser() throws Exception {
        JsonNode data = assertSuccess(getTasks(sameOwnerAgent.token(), "?scope=mine"));

        assertItems(data, "sameOwnerReceived", "sameOwnerSent");
        assertPage(data, 0, 20, 2, 1, false);
    }

    @Test
    void teamScopeIncludesOtherAgentsButExcludesOtherTeams() throws Exception {
        // 작업에 참여하지 않은 Agent도 같은 팀 전체 목록을 조회할 수 있다.
        JsonNode data = assertSuccess(getTasks(emptyAgent.token(), "?scope=team&direction=all"));

        assertItems(data, "sameOwnerReceived", "sameOwnerSent", "selfPending", "receivedCompleted",
                "receivedPending", "sentRejected", "sentFailed", "sentCompleted",
                "sentInProgress", "sentPending");
        assertPage(data, 0, 20, 10, 1, false);
        for (JsonNode item : data.path("items")) {
            assertThat(item.path("teamId").asLong()).isEqualTo(team.getId());
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "PENDING|selfPending,receivedPending,sentPending",
            "IN_PROGRESS|sentInProgress",
            "COMPLETED|receivedCompleted,sentCompleted",
            "FAILED|sentFailed",
            "REJECTED|sentRejected"
    })
    void filtersEveryTaskStatusInMineScope(String status, String labels) throws Exception {
        JsonNode data = assertSuccess(getTasks(callerAgent.token(), "?status=" + status));

        String[] expected = labels.split(",");
        assertItems(data, expected);
        assertPage(data, 0, 20, expected.length, 1, false);
        for (JsonNode item : data.path("items")) {
            assertThat(item.path("status").asText()).isEqualTo(status);
        }
    }

    @Test
    void combinesTeamStatusFilterAndPagination() throws Exception {
        JsonNode data = assertSuccess(getTasks(callerAgent.token(),
                "?scope=team&status=FAILED&size=1&page=1"));

        assertItems(data, "sentFailed");
        assertPage(data, 1, 1, 2, 2, false);
    }

    @Test
    void combinesDirectionAndStatusFilter() throws Exception {
        JsonNode data = assertSuccess(getTasks(callerAgent.token(),
                "?direction=received&status=COMPLETED"));

        assertItems(data, "receivedCompleted");
        assertPage(data, 0, 20, 1, 1, false);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "0|true|selfPending,receivedCompleted,receivedPending",
            "1|true|sentRejected,sentFailed,sentCompleted",
            "2|false|sentInProgress,sentPending"
    })
    void paginatesWithStableTotals(int page, boolean hasNext, String labels) throws Exception {
        JsonNode data = assertSuccess(getTasks(callerAgent.token(), "?size=3&page=" + page));

        assertItems(data, labels.split(","));
        assertPage(data, page, 3, 8, 3, hasNext);
    }

    @Test
    void returnsEmptyPageBeyondLastPageWithoutLosingTotals() throws Exception {
        JsonNode data = assertSuccess(getTasks(callerAgent.token(), "?size=3&page=3"));

        assertItems(data);
        assertPage(data, 3, 3, 8, 3, false);
    }

    @Test
    void returnsEmptyListForAgentWithoutTasks() throws Exception {
        JsonNode data = assertSuccess(getTasks(emptyAgent.token(), ""));

        assertItems(data);
        assertPage(data, 0, 20, 0, 0, false);
    }

    @Test
    void returnsEmptyListWhenCombinedFiltersHaveNoMatch() throws Exception {
        JsonNode data = assertSuccess(getTasks(callerAgent.token(), "?direction=received&status=FAILED"));

        assertItems(data);
        assertPage(data, 0, 20, 0, 0, false);
    }

    @Test
    void sortsEqualCreationTimesByTaskIdDescending() throws Exception {
        // createdAt이 같아도 페이지 사이에서 순서가 흔들리지 않아야 한다.
        jdbc.update("update tasks set created_at = ? where id = ?",
                Timestamp.valueOf(BASE_TIME.plusMinutes(7)), taskIds.get("sentPending"));

        JsonNode first = assertSuccess(getTasks(callerAgent.token(), "?size=1&page=0"));
        JsonNode second = assertSuccess(getTasks(callerAgent.token(), "?size=1&page=1"));
        assertItems(first, "selfPending");
        assertItems(second, "sentPending");
        assertPage(first, 0, 1, 8, 8, true);
        assertPage(second, 1, 1, 8, 8, true);
    }

    @ParameterizedTest
    @CsvSource({"sentPending,PENDING,0,false,false", "sentInProgress,IN_PROGRESS,1,true,false",
            "sentCompleted,COMPLETED,2,true,true", "sentFailed,FAILED,3,true,true",
            "sentRejected,REJECTED,4,false,true"})
    void mapsAgentAndLifecycleFields(String label, String status, int minute,
            boolean hasStarted, boolean hasCompleted) throws Exception {
        JsonNode data = assertSuccess(getTasks(callerAgent.token(), "?direction=sent&status=" + status));
        JsonNode item = findItem(data, label);

        assertThat(item.path("teamId").asLong()).isEqualTo(team.getId());
        assertThat(item.path("requesterAgent").path("agentId").asLong()).isEqualTo(callerAgent.id());
        assertThat(item.path("requesterAgent").path("name").asText()).isEqualTo(callerAgent.name());
        assertThat(item.path("receiverAgent").path("agentId").asLong()).isEqualTo(teammateAgent.id());
        assertThat(item.path("receiverAgent").path("name").asText()).isEqualTo(teammateAgent.name());
        assertThat(item.path("status").asText()).isEqualTo(status);
        LocalDateTime createdAt = BASE_TIME.plusMinutes(minute);
        assertThat(LocalDateTime.parse(item.path("createdAt").asText())).isEqualTo(createdAt);
        assertTimestamp(item.path("startedAt"), hasStarted ? createdAt.plusSeconds(10) : null);
        assertTimestamp(item.path("completedAt"), hasCompleted ? createdAt.plusSeconds(20) : null);
    }

    @Test
    void rejectsAccessToAnotherTeam() throws Exception {
        assertError(get(listPath(otherTeam.getId()), callerAgent.token()), 403, "ACCESS_DENIED");
        assertError(get(listPath(team.getId()), otherTeamAgent.token()), 403, "ACCESS_DENIED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"scope=invalid", "direction=invalid", "status=UNKNOWN", "status=pending",
            "page=-1", "page=abc", "page=2147483648", "size=0", "size=-1", "size=101",
            "size=abc", "scope=team&direction=sent", "scope=team&direction=received"})
    void rejectsInvalidQueryParameters(String query) throws Exception {
        assertError(getTasks(callerAgent.token(), "?" + query), 400, "VALIDATION_FAILED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void rejectsInvalidTeamId(String teamId) throws Exception {
        assertError(get("/api/v1/teams/" + teamId + "/tasks", callerAgent.token()),
                400, "VALIDATION_FAILED");
    }

    @Test
    void acceptsMaximumPageSize() throws Exception {
        JsonNode data = assertSuccess(getTasks(callerAgent.token(), "?size=100"));
        assertPage(data, 0, 100, 8, 1, false);
        assertThat(data.path("items").size()).isEqualTo(8);
    }

    @Test
    void rejectsRevokedToken() throws Exception {
        jdbc.update("update agents set connection_token_hash = null where id = ?", callerAgent.id());

        assertError(getTasks(callerAgent.token(), ""), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void rejectsTokenWhenOwnerHasLeftTeam() throws Exception {
        jdbc.update("delete from team_members where team_id = ? and user_id = ?",
                team.getId(), owner.getId());

        assertError(getTasks(callerAgent.token(), ""), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void requiresAgentTokenEvenWithUserSessionAndKeepsSessionIdentitySeparate() throws Exception {
        try (HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            // 다른 팀 사용자의 실제 로그인 세션을 만들어 Agent 인증과 분리되는지 확인한다.
            loginAs(client, outsider);

            assertCurrentUser(client, outsider.getId());
            assertError(client.send(request(listPath(team.getId())).GET().build(),
                    HttpResponse.BodyHandlers.ofString()), 401, "AUTHENTICATION_REQUIRED");

            JsonNode data = assertSuccess(client.send(request(listPath(team.getId()))
                    .header("Authorization", "Bearer " + callerAgent.token()).GET().build(),
                    HttpResponse.BodyHandlers.ofString()));
            assertPage(data, 0, 20, 8, 1, false);
            assertItems(data, "selfPending", "receivedCompleted", "receivedPending", "sentRejected",
                    "sentFailed", "sentCompleted", "sentInProgress", "sentPending");
            assertCurrentUser(client, outsider.getId());
        }
    }

    @Test
    void returnsCompletedTaskDetail() throws Exception {
        Long taskId = taskIds.get("sentCompleted");
        JsonNode data = assertSuccess(get(detailPath(taskId), callerAgent.token()));

        assertThat(data.path("taskId").asLong()).isEqualTo(taskId);
        assertThat(data.path("teamId").asLong()).isEqualTo(team.getId());
        assertThat(data.path("prompt").asText()).isEqualTo("목록 조회 테스트: sentCompleted");
        assertDetailAgent(data.path("requesterAgent"), callerAgent);
        assertDetailAgent(data.path("receiverAgent"), teammateAgent);
        assertThat(data.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(data.path("resultText").asText()).isEqualTo("테스트 작업 완료");
        assertThat(data.path("failureReason").isNull()).isTrue();
        assertThat(data.path("rejectionReason").isNull()).isTrue();
        assertThat(data.path("partiallyModified").isBoolean()).isTrue();
        assertThat(data.path("partiallyModified").asBoolean()).isFalse();
        assertThat(data.path("version").isIntegralNumber()).isTrue();
        assertThat(data.path("version").asLong())
                .isEqualTo(tasks.findById(taskId).orElseThrow().getVersion());
        assertEmptyArray(data.path("taskFiles"));
        assertEmptyArray(data.path("events"));
    }

    @ParameterizedTest
    @CsvSource({"sentPending,PENDING,0,false,false", "sentInProgress,IN_PROGRESS,1,true,false",
            "sentCompleted,COMPLETED,2,true,true", "sentFailed,FAILED,3,true,true",
            "sentRejected,REJECTED,4,false,true"})
    void returnsDetailFieldsForEveryStatus(String label, String status, int minute,
            boolean hasStarted, boolean hasCompleted) throws Exception {
        JsonNode data = assertSuccess(get(detailPath(taskIds.get(label)), callerAgent.token()));

        assertThat(data.path("teamName").asText()).isEqualTo(team.getName());
        assertThat(data.path("status").asText()).isEqualTo(status);
        assertNullableText(data.path("resultText"),
                "COMPLETED".equals(status) ? "테스트 작업 완료" : null);
        assertNullableText(data.path("failureReason"),
                "FAILED".equals(status) ? "테스트 작업 실패" : null);
        assertNullableText(data.path("rejectionReason"),
                "REJECTED".equals(status) ? "테스트 작업 거절" : null);
        assertThat(data.path("partiallyModified").isBoolean()).isTrue();
        assertThat(data.path("partiallyModified").asBoolean()).isEqualTo("FAILED".equals(status));
        LocalDateTime createdAt = BASE_TIME.plusMinutes(minute);
        assertTimestamp(data.path("createdAt"), createdAt);
        assertTimestamp(data.path("startedAt"), hasStarted ? createdAt.plusSeconds(10) : null);
        assertTimestamp(data.path("completedAt"), hasCompleted ? createdAt.plusSeconds(20) : null);
        assertTimestamp(data.path("lastActivityAt"), createdAt);
        assertEmptyArray(data.path("taskFiles"));
        assertEmptyArray(data.path("events"));
    }

    @Test
    void returnsFalseWhenFailedTaskHasNoPartialChanges() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                createTask("failedWithoutChanges", callerAgent, teammateAgent, TaskStatus.FAILED, false));

        JsonNode data = assertSuccess(get(detailPath(taskIds.get("failedWithoutChanges")), callerAgent.token()));

        assertThat(data.path("status").asText()).isEqualTo("FAILED");
        assertThat(data.path("partiallyModified").asBoolean()).isFalse();
        assertThat(data.path("failureReason").asText()).isEqualTo("테스트 작업 실패");
        assertThat(data.path("resultText").isNull()).isTrue();
        assertThat(data.path("rejectionReason").isNull()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"requester", "receiver", "sameOwner", "nonParticipant"})
    void allowsEveryAuthorizedAgentInSameTeamToReadDetail(String caller) throws Exception {
        TestAgent requestingAgent = switch (caller) {
            case "requester" -> callerAgent;
            case "receiver" -> teammateAgent;
            case "sameOwner" -> sameOwnerAgent;
            case "nonParticipant" -> emptyAgent;
            default -> throw new IllegalArgumentException(caller);
        };
        Long taskId = taskIds.get("sentCompleted");

        JsonNode data = assertSuccess(get(detailPath(taskId), requestingAgent.token()));

        assertThat(data.path("taskId").asLong()).isEqualTo(taskId);
        assertDetailAgent(data.path("requesterAgent"), callerAgent);
        assertDetailAgent(data.path("receiverAgent"), teammateAgent);
    }

    @Test
    void returnsDetailForSelfDirectedTask() throws Exception {
        Long taskId = taskIds.get("selfPending");
        JsonNode data = assertSuccess(get(detailPath(taskId), callerAgent.token()));

        assertThat(data.path("taskId").asLong()).isEqualTo(taskId);
        assertDetailAgent(data.path("requesterAgent"), callerAgent);
        assertDetailAgent(data.path("receiverAgent"), callerAgent);
    }

    @Test
    void returnsOnlyRequestedTaskFilesWithEveryChangeType() throws Exception {
        Long taskId = taskIds.get("sentCompleted");
        List<TaskFile> expectedFiles = new ArrayList<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            for (ChangeType type : ChangeType.values()) {
                expectedFiles.add(taskFiles.saveAndFlush(TaskFile.of(taskId,
                        "src/변경-" + type.name() + ".java", type,
                        type == ChangeType.DELETED ? null : "- 이전 코드\n+ 변경 코드")));
            }
            taskFiles.saveAndFlush(TaskFile.of(taskIds.get("receivedCompleted"),
                    "src/unrelated.java", ChangeType.ADDED, "같은 팀의 다른 작업"));
            taskFiles.saveAndFlush(TaskFile.of(taskIds.get("otherTeamPending"),
                    "src/other-team.java", ChangeType.MODIFIED, "다른 팀의 작업"));
        });

        JsonNode files = assertSuccess(get(detailPath(taskId), callerAgent.token())).path("taskFiles");

        assertThat(files.isArray()).isTrue();
        List<Long> returnedIds = new ArrayList<>();
        files.forEach(file -> returnedIds.add(file.path("fileId").asLong()));
        assertThat(returnedIds).containsExactlyInAnyOrderElementsOf(
                expectedFiles.stream().map(TaskFile::getId).toList());
        for (TaskFile expected : expectedFiles) {
            JsonNode file = null;
            for (JsonNode candidate : files) {
                if (candidate.path("fileId").asLong() == expected.getId()) {
                    file = candidate;
                    break;
                }
            }
            assertThat(file).isNotNull();
            assertThat(file.path("relativePath").asText()).isEqualTo(expected.getRelativePath());
            assertThat(file.path("changeType").asText()).isEqualTo(expected.getChangeType().name());
            assertNullableText(file.path("diffText"), expected.getDiffText());
        }
    }

    @Test
    void returnsOnlyRequestedTaskEventsInSequenceOrder() throws Exception {
        Long taskId = taskIds.get("sentCompleted");
        LocalDateTime createdAt = BASE_TIME.plusMinutes(2);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            // 기록 시각이 역전되어도 sequence 순서를 유지해야 한다.
            createEvent(taskId, TaskEventType.STATUS_CHANGED, "IN_PROGRESS → COMPLETED", createdAt.plusSeconds(20));
            createEvent(taskId, TaskEventType.CREATED, "작업 생성", createdAt);
            createEvent(taskId, TaskEventType.ACK_RECEIVED, null, createdAt.plusSeconds(5));
            createEvent(taskId, TaskEventType.PROGRESS, "진행률 50%", createdAt.plusSeconds(15));
            createEvent(taskIds.get("receivedCompleted"), TaskEventType.CREATED, "다른 작업", createdAt);
            createEvent(taskIds.get("otherTeamPending"), TaskEventType.CREATED, "다른 팀", createdAt);
        });

        JsonNode events = assertSuccess(get(detailPath(taskId), callerAgent.token())).path("events");

        assertThat(events.isArray()).isTrue();
        assertThat(events.size()).isEqualTo(4);
        assertDetailEvent(events.get(0), TaskEventType.STATUS_CHANGED, "IN_PROGRESS → COMPLETED", createdAt.plusSeconds(20));
        assertDetailEvent(events.get(1), TaskEventType.CREATED, "작업 생성", createdAt);
        assertDetailEvent(events.get(2), TaskEventType.ACK_RECEIVED, null, createdAt.plusSeconds(5));
        assertDetailEvent(events.get(3), TaskEventType.PROGRESS, "진행률 50%", createdAt.plusSeconds(15));
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).path("sequence").asText()).isEqualTo(Integer.toString(i + 1));
            assertThat(events.get(i).path("authorAgent").isNull()).isTrue();
            assertThat(events.get(i).path("questionEventId").isNull()).isTrue();
        }
    }

    @Test
    void returnsConversationAuthorsAndQuestionReferencesWithStableSequence() throws Exception {
        Long taskId = taskIds.get("sentInProgress");
        LocalDateTime sameTime = LocalDateTime.of(2026, 1, 1, 0, 20);
        List<TaskEvent> stored = new ArrayList<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            TaskEvent question = createConversationEvent(taskId, TaskEventType.QUESTION,
                    teammateAgent.id(), null, sameTime);
            stored.add(question);
            stored.add(createConversationEvent(taskId, TaskEventType.ANSWER,
                    callerAgent.id(), question.getId(), sameTime));
            stored.add(createConversationEvent(taskId, TaskEventType.ANSWER,
                    teammateAgent.id(), question.getId(), sameTime));
        });

        JsonNode events = assertSuccess(get(detailPath(taskId), callerAgent.token())).path("events");

        assertThat(events.isArray()).isTrue();
        assertThat(events.size()).isEqualTo(3);
        for (int i = 0; i < stored.size(); i++) {
            TaskEvent expected = stored.get(i);
            JsonNode actual = events.get(i);
            assertDetailEvent(actual, expected.getEventType(), "테스트 질문·답변", sameTime);
            assertThat(actual.path("eventId").asText()).isEqualTo(expected.getId().toString());
            assertThat(actual.path("sequence").asText()).isEqualTo(expected.getSequence().toString());
            assertDetailAgent(actual.path("authorAgent"), i == 1 ? callerAgent : teammateAgent);
            assertNullableText(actual.path("questionEventId"),
                    i == 0 ? null : stored.get(0).getId().toString());
        }
        // 같은 시각의 이벤트도 반복 조회 시 순서와 내용이 유지되어야 한다.
        JsonNode repeated = assertSuccess(get(detailPath(taskId), callerAgent.token())).path("events");
        assertThat(repeated).isEqualTo(events);
    }

    @Test
    void paginatesEventsWithoutDuplicatesWhenNewEventsArrive() throws Exception {
        Long taskId = taskIds.get("sentCompleted");
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            for (int i = 1; i <= 5; i++) {
                createEvent(taskId, TaskEventType.STATUS_CHANGED, "기록 " + i,
                        BASE_TIME.plusSeconds(6 - i));
            }
            createEvent(taskIds.get("receivedCompleted"), TaskEventType.CREATED, "다른 작업", BASE_TIME);
            createEvent(taskIds.get("otherTeamPending"), TaskEventType.CREATED, "다른 팀", BASE_TIME);
        });
        Map<String, Object> taskBefore = jdbc.queryForMap("select * from tasks where id = ?", taskId);

        JsonNode first = assertSuccess(get(eventsPath(taskId) + "?limit=2", callerAgent.token()));
        assertEventPage(first, taskId, "4", true, "4", "5");

        // 최신 기록이 추가되어도 이전 페이지의 경계가 밀리지 않아야 한다.
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                createEvent(taskId, TaskEventType.STATUS_CHANGED, "새 기록", BASE_TIME.plusMinutes(1)));
        List<Map<String, Object>> eventsBefore = jdbc.queryForList(
                "select * from task_events where task_id = ? order by sequence", taskId);

        JsonNode second = assertSuccess(get(eventsPath(taskId) + "?limit=2&beforeSequence="
                + first.path("nextBeforeSequence").asText(), callerAgent.token()));
        assertEventPage(second, taskId, "2", true, "2", "3");
        JsonNode last = assertSuccess(get(eventsPath(taskId) + "?limit=2&beforeSequence="
                + second.path("nextBeforeSequence").asText(), callerAgent.token()));
        assertEventPage(last, taskId, null, false, "1");
        JsonNode empty = assertSuccess(get(eventsPath(taskId) + "?limit=2&beforeSequence=1", callerAgent.token()));
        assertEventPage(empty, taskId, null, false);

        assertThat(jdbc.queryForMap("select * from tasks where id = ?", taskId)).isEqualTo(taskBefore);
        assertThat(jdbc.queryForList("select * from task_events where task_id = ? order by sequence", taskId))
                .isEqualTo(eventsBefore);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"default|50", "1|1", "100|100"})
    void appliesEventPageDefaultAndLimitBoundaries(String limit, int expectedSize) throws Exception {
        Long taskId = taskIds.get("sentCompleted");
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            for (int i = 1; i <= 101; i++) {
                createEvent(taskId, TaskEventType.ACK_RECEIVED, "전달 기록 " + i, BASE_TIME);
            }
        });

        String query = "default".equals(limit) ? "" : "?limit=" + limit;
        JsonNode data = assertSuccess(get(eventsPath(taskId) + query, callerAgent.token()));

        assertThat(data.path("items").size()).isEqualTo(expectedSize);
        assertThat(data.path("hasNext").asBoolean()).isTrue();
        assertNullableText(data.path("nextBeforeSequence"), Integer.toString(102 - expectedSize));
        for (int i = 0; i < expectedSize; i++) {
            JsonNode item = data.path("items").get(i);
            assertDetailEvent(item, TaskEventType.ACK_RECEIVED, "전달 기록 " + (102 - expectedSize + i), BASE_TIME);
            assertThat(item.path("sequence").asText()).isEqualTo(Integer.toString(102 - expectedSize + i));
            assertThat(item.path("authorAgent").isNull()).isTrue();
            assertThat(item.path("questionEventId").isNull()).isTrue();
        }
    }

    @Test
    void returnsSameEventFieldsAsDetailWhenQuestionIsOnAnotherPage() throws Exception {
        Long taskId = taskIds.get("sentInProgress");
        List<TaskEvent> stored = new ArrayList<>();
        String body = "첫 줄\n```java\n  return true;\n```\n<script>alert('text')</script> 😀";
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            TaskEvent question = createConversationEvent(taskId, TaskEventType.QUESTION,
                    teammateAgent.id(), null, BASE_TIME);
            stored.add(question);
            stored.add(createConversationEvent(taskId, TaskEventType.ANSWER,
                    callerAgent.id(), question.getId(), BASE_TIME));
            stored.add(createConversationEvent(taskId, TaskEventType.ANSWER,
                    teammateAgent.id(), question.getId(), BASE_TIME));
            jdbc.update("update task_events set content = ? where id = ?", body, stored.get(1).getId());
        });

        JsonNode detail = assertSuccess(get(detailPath(taskId), callerAgent.token())).path("events");
        // 같은 팀의 작업 비참여 Agent도 읽을 수 있다.
        JsonNode first = assertSuccess(get(eventsPath(taskId) + "?limit=2", emptyAgent.token()));
        assertEventPage(first, taskId, "2", true, "2", "3");
        assertThat(first.path("items").get(0)).isEqualTo(detail.get(1));
        assertThat(first.path("items").get(1)).isEqualTo(detail.get(2));
        assertDetailAgent(first.path("items").get(0).path("authorAgent"), callerAgent);
        assertDetailAgent(first.path("items").get(1).path("authorAgent"), teammateAgent);
        assertThat(first.path("items").get(0).path("message").asText()).isEqualTo(body);
        for (JsonNode item : first.path("items")) {
            assertNullableText(item.path("questionEventId"), stored.get(0).getId().toString());
        }

        JsonNode last = assertSuccess(get(eventsPath(taskId) + "?limit=2&beforeSequence=2", callerAgent.token()));
        assertEventPage(last, taskId, null, false, "1");
        assertThat(last.path("items").get(0)).isEqualTo(detail.get(0));
        // 전체 이벤트 수가 limit와 같으면 다음 페이지가 없어야 한다.
        JsonNode exact = assertSuccess(get(eventsPath(taskId) + "?limit=3", callerAgent.token()));
        assertEventPage(exact, taskId, null, false, "1", "2", "3");
        assertThat(exact.path("items")).isEqualTo(detail);
    }

    @Test
    void acceptsMissingSequenceBoundaryAndGapsWithoutRenumbering() throws Exception {
        Long taskId = taskIds.get("sentCompleted");
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            for (int i = 1; i <= 3; i++) {
                createEvent(taskId, TaskEventType.CREATED, null, BASE_TIME);
            }
            jdbc.update("delete from task_events where task_id = ? and sequence = 2", taskId);
        });

        JsonNode data = assertSuccess(get(eventsPath(taskId) + "?beforeSequence=2", callerAgent.token()));
        assertEventPage(data, taskId, null, false, "1");
        JsonNode all = assertSuccess(get(eventsPath(taskId) + "?beforeSequence=9223372036854775807", callerAgent.token()));
        assertEventPage(all, taskId, null, false, "1", "3");
        assertThat(all.path("items").get(0).path("message").isNull()).isTrue();
    }

    @Test
    void returnsEmptyEventPageForExistingTaskWithoutHistory() throws Exception {
        Long taskId = taskIds.get("sentPending");
        JsonNode data = assertSuccess(get(eventsPath(taskId), callerAgent.token()));
        assertEventPage(data, taskId, null, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"beforeSequence=0", "beforeSequence=-1", "beforeSequence=01",
            "beforeSequence=abc", "beforeSequence=1.5", "beforeSequence=",
            "beforeSequence=9223372036854775808", "beforeSequence=99999999999999999999",
            "limit=0", "limit=-1", "limit=101", "limit=abc", "limit=1.5", "limit=2147483648"})
    void rejectsInvalidEventPageParameters(String query) throws Exception {
        assertError(get(eventsPath(taskIds.get("sentCompleted")) + "?" + query, callerAgent.token()),
                400, "VALIDATION_FAILED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void rejectsInvalidEventPageTaskId(String taskId) throws Exception {
        assertError(get("/api/v1/tasks/" + taskId + "/events", callerAgent.token()), 400, "VALIDATION_FAILED");
    }

    @Test
    void protectsEventPageWithAgentAuthenticationAndTeamAuthorization() throws Exception {
        String path = eventsPath(taskIds.get("sentCompleted"));
        assertError(get(path, null), 401, "AUTHENTICATION_REQUIRED");
        assertError(get(path, "unknown-token"), 401, "AUTHENTICATION_REQUIRED");
        // 이벤트가 없어도 접근 권한 검사를 생략하지 않는다.
        assertError(get(path, otherTeamAgent.token()), 403, "ACCESS_DENIED");
        assertError(get(eventsPath(taskIds.get("otherTeamPending")), callerAgent.token()), 403, "ACCESS_DENIED");
        assertError(get(eventsPath(Long.MAX_VALUE), callerAgent.token()), 404, "RESOURCE_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"revoked", "leftTeam"})
    void rejectsEventPageWhenAgentCredentialsAreNoLongerValid(String condition) throws Exception {
        if ("revoked".equals(condition)) {
            jdbc.update("update agents set connection_token_hash = null where id = ?", callerAgent.id());
        } else {
            jdbc.update("delete from team_members where team_id = ? and user_id = ?", team.getId(), owner.getId());
        }
        assertError(get(eventsPath(taskIds.get("sentCompleted")), callerAgent.token()),
                401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void doesNotUseUserSessionAsEventPageAgentIdentity() throws Exception {
        try (HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            loginAs(client, outsider);
            String path = eventsPath(taskIds.get("sentCompleted"));
            assertError(client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString()),
                    401, "AUTHENTICATION_REQUIRED");
            JsonNode data = assertSuccess(client.send(request(path)
                    .header("Authorization", "Bearer " + callerAgent.token()).GET().build(),
                    HttpResponse.BodyHandlers.ofString()));
            assertThat(data.path("taskId").asLong()).isEqualTo(taskIds.get("sentCompleted"));
            assertCurrentUser(client, outsider.getId());
        }
    }

    private String eventsPath(Long taskId) {
        return detailPath(taskId) + "/events";
    }

    private void assertEventPage(JsonNode data, Long taskId, String cursor, boolean hasNext, String... sequences) {
        assertThat(data.path("taskId").asLong()).isEqualTo(taskId);
        assertThat(data.path("items").isArray()).isTrue();
        List<String> actual = new ArrayList<>();
        data.path("items").forEach(item -> {
            assertThat(item.path("sequence").isTextual()).isTrue();
            actual.add(item.path("sequence").asText());
        });
        assertThat(actual).containsExactly(sequences);
        assertNullableText(data.path("nextBeforeSequence"), cursor);
        assertThat(data.path("hasNext").isBoolean()).isTrue();
        assertThat(data.path("hasNext").asBoolean()).isEqualTo(hasNext);
        assertThat(data.has("prompt")).isFalse();
        assertThat(data.has("taskFiles")).isFalse();
    }

    @Test
    void rejectsDetailWithoutAgentToken() throws Exception {
        assertError(get(detailPath(taskIds.get("sentCompleted")), null), 401, "AUTHENTICATION_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer unknown-token", "Basic credentials", "Bearer", "Bearer first second"})
    void rejectsInvalidDetailAuthorizationHeader(String authorization) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var request = request(detailPath(taskIds.get("sentCompleted")))
                    .header("Authorization", authorization).GET().build();
            assertError(client.send(request, HttpResponse.BodyHandlers.ofString()),
                    401, "AUTHENTICATION_REQUIRED");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void rejectsInvalidDetailTaskId(String taskId) throws Exception {
        assertError(get("/api/v1/tasks/" + taskId, callerAgent.token()), 400, "VALIDATION_FAILED");
    }

    @Test
    void returnsNotFoundForMissingDetailTask() throws Exception {
        assertThat(tasks.existsById(Long.MAX_VALUE)).isFalse();

        assertError(get(detailPath(Long.MAX_VALUE), callerAgent.token()), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void rejectsDetailAccessAcrossTeams() throws Exception {
        assertError(get(detailPath(taskIds.get("sentCompleted")), otherTeamAgent.token()), 403, "ACCESS_DENIED");
        assertError(get(detailPath(taskIds.get("otherTeamPending")), callerAgent.token()), 403, "ACCESS_DENIED");
    }

    @Test
    void rejectsDetailWithRevokedToken() throws Exception {
        jdbc.update("update agents set connection_token_hash = null where id = ?", callerAgent.id());

        assertError(get(detailPath(taskIds.get("sentCompleted")), callerAgent.token()),
                401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void rejectsDetailWhenAgentOwnerHasLeftTeam() throws Exception {
        jdbc.update("delete from team_members where team_id = ? and user_id = ?", team.getId(), owner.getId());

        assertError(get(detailPath(taskIds.get("sentCompleted")), callerAgent.token()),
                401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void requiresDetailAgentTokenAndDoesNotReplaceUserSessionIdentity() throws Exception {
        try (HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            loginAs(client, outsider);
            assertCurrentUser(client, outsider.getId());
            String path = detailPath(taskIds.get("sentCompleted"));
            assertError(client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString()),
                    401, "AUTHENTICATION_REQUIRED");
            assertError(client.send(request(path).header("Authorization", "Bearer unknown-token").GET().build(),
                    HttpResponse.BodyHandlers.ofString()), 401, "AUTHENTICATION_REQUIRED");

            JsonNode data = assertSuccess(client.send(request(path)
                    .header("Authorization", "Bearer " + callerAgent.token()).GET().build(),
                    HttpResponse.BodyHandlers.ofString()));
            assertThat(data.path("taskId").asLong()).isEqualTo(taskIds.get("sentCompleted"));
            assertDetailAgent(data.path("requesterAgent"), callerAgent);
            assertCurrentUser(client, outsider.getId());
        }
    }

    @Test
    void readingDetailDoesNotChangeTaskVersionOrCreateHistory() throws Exception {
        Long taskId = taskIds.get("sentCompleted");
        Map<String, Object> before = jdbc.queryForMap("select * from tasks where id = ?", taskId);

        JsonNode first = assertSuccess(get(detailPath(taskId), callerAgent.token()));
        JsonNode second = assertSuccess(get(detailPath(taskId), callerAgent.token()));

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForMap("select * from tasks where id = ?", taskId)).isEqualTo(before);
        assertThat(taskFiles.findByTaskId(taskId)).isEmpty();
        assertThat(taskEvents.findByTaskIdOrderByCreatedAtAsc(taskId)).isEmpty();
    }

    private void loginAs(HttpClient client, User user) throws Exception {
        JsonNode csrf = assertSuccess(client.send(request("/api/v1/auth/csrf").GET().build(),
                HttpResponse.BodyHandlers.ofString()));
        var login = request("/api/v1/auth/login")
                .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        Map.of("email", user.getEmail(), "password", TEST_PASSWORD)))).build();
        assertSuccess(client.send(login, HttpResponse.BodyHandlers.ofString()));
    }

    private String detailPath(Long taskId) {
        return "/api/v1/tasks/" + taskId;
    }

    private void assertDetailAgent(JsonNode agent, TestAgent expected) {
        assertThat(agent.path("agentId").asLong()).isEqualTo(expected.id());
        assertThat(agent.path("name").asText()).isEqualTo(expected.name());
    }

    private void assertEmptyArray(JsonNode value) {
        assertThat(value.isArray()).isTrue();
        assertThat(value.size()).isZero();
    }

    private void assertNullableText(JsonNode value, String expected) {
        if (expected == null) {
            assertThat(value.isNull()).isTrue();
        } else {
            assertThat(value.isTextual()).isTrue();
            assertThat(value.asText()).isEqualTo(expected);
        }
    }

    private void assertDetailEvent(JsonNode event, TaskEventType type, String message, LocalDateTime createdAt) {
        assertThat(event.path("eventId").isTextual()).isTrue();
        assertThat(event.path("eventId").asText()).matches("[1-9][0-9]*");
        assertThat(event.path("sequence").isTextual()).isTrue();
        assertThat(event.path("sequence").asText()).matches("[1-9][0-9]*");
        assertThat(event.path("eventType").asText()).isEqualTo(type.name());
        assertNullableText(event.path("message"), message);
        assertThat(event.has("content")).isFalse();
        assertTimestamp(event.path("createdAt"), createdAt);
    }

    private void createEvent(Long taskId, TaskEventType type, String content, LocalDateTime createdAt) {
        TaskEvent event = taskEvents.saveAndFlush(TaskEvent.of(taskId, type, content));
        jdbc.update("update task_events set created_at = ? where id = ?", Timestamp.valueOf(createdAt), event.getId());
    }

    private TaskEvent createConversationEvent(Long taskId, TaskEventType type,
            Long authorAgentId, Long questionEventId, LocalDateTime utcCreatedAt) {
        TaskEvent event = taskEvents.saveAndFlush(TaskEvent.of(taskId, type, "테스트 질문·답변",
                authorAgentId, questionEventId, UUID.randomUUID().toString()));
        jdbc.update("update task_events set created_at = ? where id = ?",
                Timestamp.valueOf(utcCreatedAt), event.getId());
        return event;
    }

    private void assertCurrentUser(HttpClient client, Long userId) throws Exception {
        JsonNode currentUser = assertSuccess(client.send(request("/api/v1/auth/users/me").GET().build(),
                HttpResponse.BodyHandlers.ofString()));
        assertThat(currentUser.path("userId").asLong()).isEqualTo(userId);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10));
    }

    private String listPath(Long teamId) {
        return "/api/v1/teams/" + teamId + "/tasks";
    }

    private HttpResponse<String> getTasks(String token, String query) throws Exception {
        return get(listPath(team.getId()) + query, token);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var builder = request(path).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private JsonNode assertSuccess(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as("HTTP response: %s", response.body()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("code").isNull()).isTrue();
        assertThat(body.path("message").isNull()).isTrue();
        assertThat(body.path("data").isObject()).isTrue();
        return body.path("data");
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as("HTTP response: %s", response.body()).isEqualTo(status);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("data").isNull()).isTrue();
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("message").asText()).isNotBlank();
    }

    private void assertItems(JsonNode data, String... labels) {
        assertThat(data.path("items").isArray()).isTrue();
        List<Long> actual = new ArrayList<>();
        data.path("items").forEach(item -> actual.add(item.path("taskId").asLong()));
        List<Long> expected = new ArrayList<>();
        for (String label : labels) {
            assertThat(taskIds).containsKey(label);
            expected.add(taskIds.get(label));
        }
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    private void assertPage(JsonNode data, int page, int size, long total, int totalPages, boolean hasNext) {
        assertThat(data.path("page").asInt()).isEqualTo(page);
        assertThat(data.path("size").asInt()).isEqualTo(size);
        assertThat(data.path("totalElements").asLong()).isEqualTo(total);
        assertThat(data.path("totalPages").asInt()).isEqualTo(totalPages);
        assertThat(data.path("hasNext").asBoolean()).isEqualTo(hasNext);
    }

    private JsonNode findItem(JsonNode data, String label) {
        for (JsonNode item : data.path("items")) {
            if (item.path("taskId").asLong() == taskIds.get(label)) {
                return item;
            }
        }
        throw new AssertionError("응답에서 작업을 찾을 수 없습니다: " + label);
    }

    private void assertTimestamp(JsonNode value, LocalDateTime expected) {
        if (expected == null) {
            assertThat(value.isNull()).isTrue();
        } else {
            assertThat(LocalDateTime.parse(value.asText())).isEqualTo(expected);
        }
    }

    @BeforeEach
    void prepareTestData() {
        taskIds = new LinkedHashMap<>();
        taskSequence = 0;

        // HTTP 요청은 별도 스레드에서 실행되므로 준비 데이터를 먼저 커밋한다.
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
            owner = createUser("목록 조회 사용자", passwordHash);
            teammate = createUser("같은 팀 사용자", passwordHash);
            outsider = createUser("다른 팀 사용자", passwordHash);

            team = teams.save(Team.create("조회 테스트 팀", UUID.randomUUID().toString()));
            otherTeam = teams.save(Team.create("다른 팀", UUID.randomUUID().toString()));
            members.save(TeamMember.create(team.getId(), owner.getId(), MemberRole.OWNER));
            members.save(TeamMember.create(team.getId(), teammate.getId(), MemberRole.MEMBER));
            members.save(TeamMember.create(otherTeam.getId(), outsider.getId(), MemberRole.OWNER));

            callerAgent = createAgent(team, owner, "호출 Agent");
            sameOwnerAgent = createAgent(team, owner, "같은 사용자 소유의 다른 Agent");
            teammateAgent = createAgent(team, teammate, "팀원 Agent");
            emptyAgent = createAgent(team, owner, "작업이 없는 Agent");
            otherTeamAgent = createAgent(otherTeam, outsider, "다른 팀 Agent");

            createTask("sentPending", callerAgent, teammateAgent, TaskStatus.PENDING);
            createTask("sentInProgress", callerAgent, teammateAgent, TaskStatus.IN_PROGRESS);
            createTask("sentCompleted", callerAgent, teammateAgent, TaskStatus.COMPLETED);
            createTask("sentFailed", callerAgent, teammateAgent, TaskStatus.FAILED);
            createTask("sentRejected", callerAgent, teammateAgent, TaskStatus.REJECTED);
            createTask("receivedPending", teammateAgent, callerAgent, TaskStatus.PENDING);
            createTask("receivedCompleted", teammateAgent, callerAgent, TaskStatus.COMPLETED);
            createTask("selfPending", callerAgent, callerAgent, TaskStatus.PENDING);
            createTask("sameOwnerSent", sameOwnerAgent, teammateAgent, TaskStatus.PENDING);
            createTask("sameOwnerReceived", teammateAgent, sameOwnerAgent, TaskStatus.FAILED);
            createTask("otherTeamPending", otherTeamAgent, otherTeamAgent, TaskStatus.PENDING);
        });
    }

    private User createUser(String name, String passwordHash) {
        return users.save(User.create(
                "task-query-" + UUID.randomUUID() + "@example.com",
                name, null, passwordHash));
    }

    private TestAgent createAgent(Team targetTeam, User user, String name) {
        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        Agent agent = Agent.create(targetTeam.getId(), user.getId(), name,
                "작업 목록 조회 테스트", ToolType.CODEX, "task-query-test");
        agent.issueConnectionToken(AgentTokenHash.sha256(token));
        agent = agents.saveAndFlush(agent);

        return new TestAgent(agent.getId(), targetTeam.getId(), user.getId(), name, token);
    }

    private void createTask(String label, TestAgent requester, TestAgent receiver, TaskStatus status) {
        createTask(label, requester, receiver, status, true);
    }

    private void createTask(String label, TestAgent requester, TestAgent receiver,
            TaskStatus status, boolean partiallyModified) {
        Task task = tasks.saveAndFlush(Task.create(
                UUID.randomUUID().toString(), requester.teamId(), requester.id(),
                requester.ownerUserId(), receiver.id(), "목록 조회 테스트: " + label));

        if (status == TaskStatus.IN_PROGRESS
                || status == TaskStatus.COMPLETED
                || status == TaskStatus.FAILED) {
            task.accept(receiver.id());
            tasks.flush();
        }
        switch (status) {
            case COMPLETED -> task.complete(receiver.id(), "테스트 작업 완료");
            case FAILED -> task.fail(receiver.id(), "테스트 작업 실패", partiallyModified);
            case REJECTED -> task.reject(receiver.id(), "테스트 작업 거절");
            case PENDING, IN_PROGRESS -> { }
        }
        tasks.flush();

        // 정렬과 페이지 검증이 실행 시각에 의존하지 않도록 테스트 데이터의 시각을 고정한다.
        LocalDateTime createdAt = BASE_TIME.plusMinutes(taskSequence++);
        Timestamp startedAt = task.getStartedAt() == null
                ? null : Timestamp.valueOf(createdAt.plusSeconds(10));
        Timestamp completedAt = task.getCompletedAt() == null
                ? null : Timestamp.valueOf(createdAt.plusSeconds(20));
        jdbc.update("update tasks set created_at = ?, started_at = ?, completed_at = ? where id = ?",
                Timestamp.valueOf(createdAt), startedAt, completedAt, task.getId());
        taskIds.put(label, task.getId());
    }

    private record TestAgent(Long id, Long teamId, Long ownerUserId, String name, String token) {
    }
}
