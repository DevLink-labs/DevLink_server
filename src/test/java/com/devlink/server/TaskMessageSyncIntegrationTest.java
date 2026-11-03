package com.devlink.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import com.devlink.server.common.AgentTokenHash;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.teammember.TeamMember;
import com.devlink.server.domain.user.User;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskEventRepository;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TeamMemberRepository;
import com.devlink.server.repository.TeamRepository;
import com.devlink.server.repository.UserRepository;
import com.devlink.server.service.TaskMessageCursorCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 PostgreSQL 커밋 순서와 HTTP 인증 경계를 함께 검증한다. */
class TaskMessageSyncIntegrationTest extends AbstractIntegrationTest {
    @LocalServerPort private int port;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private TeamMemberRepository members;
    @Autowired private AgentRepository agents;
    @Autowired private TaskRepository tasks;
    @Autowired private TaskEventRepository events;
    @Value("${devlink.task-message-sync.cursor-secret}") private String cursorSecret;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private PasswordEncoder passwords;
    private TestAgent a;
    private TestAgent b;
    private TestAgent nonParticipant;
    private TestAgent outsider;
    private long firstTask;
    private long secondTask;
    private long selfTask;

    @BeforeEach
    void setup() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var owner = users.save(User.create(UUID.randomUUID() + "@example.com", "동기화 사용자", null, "test"));
            var team = teams.save(Team.create("동기화 팀", UUID.randomUUID().toString()));
            var otherTeam = teams.save(Team.create("다른 팀", UUID.randomUUID().toString()));
            members.save(TeamMember.create(team.getId(), owner.getId(), MemberRole.OWNER));
            members.save(TeamMember.create(otherTeam.getId(), owner.getId(), MemberRole.OWNER));
            a = agent(team, owner, "A");
            b = agent(team, owner, "B");
            nonParticipant = agent(team, owner, "비참여 Agent");
            outsider = agent(otherTeam, owner, "다른 팀 Agent");
            firstTask = task(a, b);
            secondTask = task(b, a);
            selfTask = task(a, a);
        });
    }

    @Test
    void returnsOnlyReceivedMessagesWithoutBodyAndDoesNotChangeBusinessState() throws Exception {
        long receivedQuestion = event(firstTask, b, "QUESTION");
        long receivedAnswer = event(firstTask, b, "ANSWER");
        long secondAnswer = event(firstTask, b, "ANSWER");
        long reverseDirection = event(secondTask, b, "QUESTION");
        event(firstTask, a, "QUESTION");
        event(selfTask, a, "QUESTION");
        event(firstTask, nonParticipant, "ANSWER");
        long unrelatedTask = new TransactionTemplate(transactions).execute(tx -> task(b, nonParticipant));
        long unrelatedEvent = event(unrelatedTask, b, "QUESTION");
        long otherTeamTask = new TransactionTemplate(transactions).execute(tx -> task(outsider, outsider));
        event(otherTeamTask, outsider, "QUESTION");
        events.saveAndFlush(TaskEvent.of(firstTask, TaskEventType.PROGRESS, "진행 이력"));
        var before = jdbc.queryForMap("SELECT status, version, started_at, completed_at FROM tasks WHERE id = ?", firstTask);
        long eventCount = events.count();
        long outboxCount = jdbc.queryForObject("SELECT count(*) FROM outbox_events", Long.class);

        JsonNode data = success(pull(a, null, null));

        assertIds(data, receivedQuestion, receivedAnswer, secondAnswer, reverseDirection);
        assertThat(data.path("hasNext").asBoolean()).isFalse();
        assertThat(data.path("nextCursor").asText()).isNotBlank();
        for (JsonNode item : data.path("items")) {
            assertThat(item.has("notificationId")).isFalse();
            assertThat(item.has("message")).isFalse();
            assertThat(item.path("sequence").isTextual()).isTrue();
            assertThat(item.path("createdAt").asText()).endsWith("Z");
        }
        assertThat(jdbc.queryForMap("SELECT status, version, started_at, completed_at FROM tasks WHERE id = ?", firstTask))
                .isEqualTo(before);
        assertThat(events.count()).isEqualTo(eventCount);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Long.class)).isEqualTo(outboxCount);
        assertIds(success(pull(nonParticipant, null, null)), unrelatedEvent);
    }

    @Test
    void restoresMessagesRegardlessOfOutboxStateAndTaskCompletion() throws Exception {
        List<Long> expected = new ArrayList<>();
        for (String status : List.of("PENDING", "PUBLISHED", "FAILED")) {
            long eventId = event(firstTask, b, "ANSWER");
            expected.add(eventId);
            jdbc.update("""
                    INSERT INTO outbox_events (outbox_id, event_type, aggregate_type, aggregate_id,
                        destination, message, status)
                    VALUES (?, 'TASK_MESSAGE_AVAILABLE', 'TASK', ?, 'task-messages', CAST(? AS jsonb), ?)
                    """, UUID.randomUUID(), Long.toString(firstTask), "{\"eventId\":\"" + eventId + "\"}", status);
        }
        expected.add(event(firstTask, b, "QUESTION")); // outbox가 없어도 원본만으로 복구한다.
        jdbc.update("UPDATE tasks SET status = 'COMPLETED' WHERE id = ?", firstTask);

        assertIds(success(pull(a, null, null)), expected.stream().mapToLong(Long::longValue).toArray());
    }

    @Test
    void paginatesTasksFairlyAndReplaysSameCursorWithoutDuplicates() throws Exception {
        long one = event(firstTask, b, "QUESTION");
        long two = event(firstTask, b, "ANSWER");
        long three = event(secondTask, b, "QUESTION");
        JsonNode pageOne = success(pull(a, null, "1"));
        assertIds(pageOne, one);
        assertThat(pageOne.path("hasNext").asBoolean()).isTrue();
        String cursor = pageOne.path("nextCursor").asText();
        long newer = event(firstTask, b, "ANSWER");

        JsonNode pageTwo = success(pull(a, cursor, "1"));
        assertIds(pageTwo, three);
        assertIds(success(pull(a, cursor, "1")), three);
        JsonNode pageThree = success(pull(a, pageTwo.path("nextCursor").asText(), "1"));
        assertIds(pageThree, two);
        assertThat(pageThree.path("hasNext").asBoolean()).isTrue();
        JsonNode pageFour = success(pull(a, pageThree.path("nextCursor").asText(), "1"));
        assertIds(pageFour, newer);
        assertThat(pageFour.path("hasNext").asBoolean()).isFalse();
        assertIds(success(pull(a, pageFour.path("nextCursor").asText(), "1")));
    }

    @Test
    void lowerEventIdCommittedAfterHigherIdIsRecoveredWithPerTaskPositions() throws Exception {
        try (Connection slow = dataSource.getConnection()) {
            slow.setAutoCommit(false);
            long late = insert(slow, firstTask, b.id(), "QUESTION");
            long early = event(secondTask, b, "ANSWER");
            assertThat(late).isLessThan(early);

            JsonNode initial = success(pull(a, null, null));
            assertIds(initial, early);
            slow.commit();

            JsonNode recovered = success(pull(a, initial.path("nextCursor").asText(), null));
            assertIds(recovered, late);
            assertIds(success(pull(a, recovered.path("nextCursor").asText(), null)));
        }
    }

    @Test
    void commitDuringPaginationDoesNotGetSkippedByPagePosition() throws Exception {
        try (Connection slow = dataSource.getConnection()) {
            slow.setAutoCommit(false);
            long late = insert(slow, firstTask, b.id(), "QUESTION");
            long first = event(secondTask, b, "QUESTION");
            long second = event(secondTask, b, "ANSWER");
            JsonNode pageOne = success(pull(a, null, "1"));
            assertIds(pageOne, first);
            assertThat(pageOne.path("hasNext").asBoolean()).isTrue();
            slow.commit();

            JsonNode pageTwo = success(pull(a, pageOne.path("nextCursor").asText(), "1"));
            assertIds(pageTwo, late);
            assertThat(pageTwo.path("hasNext").asBoolean()).isTrue();
            JsonNode pageThree = success(pull(a, pageTwo.path("nextCursor").asText(), "1"));
            assertIds(pageThree, second);
            assertThat(pageThree.path("hasNext").asBoolean()).isFalse();
        }
    }

    @Test
    void emptyPollDoesNotLoseAnAlreadyStartedTransaction() throws Exception {
        try (Connection slow = dataSource.getConnection()) {
            slow.setAutoCommit(false);
            long late = insert(slow, firstTask, b.id(), "QUESTION");
            JsonNode empty = success(pull(a, null, null));
            assertIds(empty);
            slow.commit();
            assertIds(success(pull(a, empty.path("nextCursor").asText(), null)), late);
        }
    }

    @Test
    void rolledBackEventsNeverAppearAfterEmptyPoll() throws Exception {
        String cursor;
        try (Connection rolledBack = dataSource.getConnection()) {
            rolledBack.setAutoCommit(false);
            insert(rolledBack, firstTask, b.id(), "QUESTION");
            cursor = success(pull(a, null, null)).path("nextCursor").asText();
            rolledBack.rollback();
        }
        long committed = event(firstTask, b, "QUESTION");
        assertIds(success(pull(a, cursor, null)), committed);
    }

    @Test
    void preservesTaskSequenceWhenTimestampsAreEqual() throws Exception {
        long first = event(firstTask, b, "QUESTION");
        long second = event(firstTask, b, "ANSWER");
        jdbc.update("UPDATE task_events SET created_at = TIMESTAMP '2026-11-03 05:30:00' WHERE task_id = ?", firstTask);
        JsonNode data = success(pull(a, null, null));
        assertIds(data, first, second);
        assertThat(data.path("items").get(0).path("sequence").asText()).isEqualTo("1");
        // 답변 대상 질문도 원본 이력에 저장되지만 본인 작성이므로 수신 목록에서는 제외된다.
        assertThat(Long.parseLong(data.path("items").get(1).path("sequence").asText())).isGreaterThan(1);
    }

    @Test
    void rejectsMissingInvalidAndRevokedMembershipAuthentication() throws Exception {
        assertError(pull(null, null, null), 401, "AUTHENTICATION_REQUIRED");
        assertError(pull(new TestAgent(0, 0, 0, "invalid-token"), null, null), 401, "AUTHENTICATION_REQUIRED");
        jdbc.update("DELETE FROM team_members WHERE team_id = ? AND user_id = ?", a.teamId(), a.ownerId());
        assertError(pull(a, null, null), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void userLoginSessionCannotReplaceAgentTokenOrBeOverwrittenByAgentRequest() throws Exception {
        String password = "Sync-test-password-123!";
        User user = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "로그인 사용자", null,
                passwords.encode(password)));
        String origin = "http://localhost:" + port;
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            JsonNode csrf = success(client.send(HttpRequest.newBuilder(URI.create(origin + "/api/v1/auth/csrf"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString()));
            var login = HttpRequest.newBuilder(URI.create(origin + "/api/v1/auth/login"))
                    .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                            Map.of("email", user.getEmail(), "password", password)))).build();
            success(client.send(login, HttpResponse.BodyHandlers.ofString()));
            assertError(client.send(HttpRequest.newBuilder(URI.create(baseUrl())).GET().build(),
                    HttpResponse.BodyHandlers.ofString()), 401, "AUTHENTICATION_REQUIRED");
            success(client.send(HttpRequest.newBuilder(URI.create(baseUrl()))
                    .header("Authorization", "Bearer " + a.token()).GET().build(), HttpResponse.BodyHandlers.ofString()));
            JsonNode currentUser = success(client.send(HttpRequest.newBuilder(URI.create(origin + "/api/v1/auth/users/me"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString()));
            assertThat(currentUser.path("userId").asLong()).isEqualTo(user.getId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "-1", "abc", "1.5", "2147483648"})
    void rejectsInvalidLimit(String limit) throws Exception {
        assertError(pull(a, null, limit), 400, "VALIDATION_FAILED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-a-cursor", "e30.invalid", "opaque-cursor-001"})
    void rejectsMalformedCursor(String cursor) throws Exception {
        assertError(pull(a, cursor, null), 400, "VALIDATION_FAILED");
    }

    @Test
    void rejectsTamperingAndAnotherAgentsCursor() throws Exception {
        String cursor = success(pull(a, null, null)).path("nextCursor").asText();
        char replacement = cursor.charAt(0) == 'A' ? 'B' : 'A';
        assertError(pull(a, replacement + cursor.substring(1), null), 400, "VALIDATION_FAILED");
        assertError(pull(b, cursor, null), 403, "ACCESS_DENIED");
        assertError(pull(outsider, cursor, null), 403, "ACCESS_DENIED");
        // DB 테이블 없이 동일한 환경설정 키만으로 재시작·다중 인스턴스의 cursor를 검증한다.
        var codec = new TaskMessageCursorCodec(json, cursorSecret);
        assertThat(codec.decode(cursor, new AgentPrincipal(a.id(), a.ownerId(), a.teamId())).agentId()).isEqualTo(a.id());
    }

    @Test
    void unsupportedMethodReturns405WithAgentAuthentication() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create(baseUrl()))
                    .header("Authorization", "Bearer " + a.token()).POST(HttpRequest.BodyPublishers.noBody()).build();
            assertError(client.send(request, HttpResponse.BodyHandlers.ofString()), 405, "METHOD_NOT_ALLOWED");
        }
    }

    @Test
    void compressedCursorPreservesManyIndependentTaskPositionsAcrossInstances() {
        var positions = new java.util.TreeMap<Long, Long>();
        for (long taskId = 1; taskId <= 1000; taskId++) positions.put(taskId, taskId * 2);
        var cursor = new TaskMessageCursorCodec.Cursor(2, a.id(), a.ownerId(), a.teamId(), positions, 1000);
        var writer = new TaskMessageCursorCodec(json, cursorSecret);
        var reader = new TaskMessageCursorCodec(json, cursorSecret);
        String token = writer.encode(cursor);
        assertThat(token.length()).isLessThan(8000);
        assertThat(reader.decode(token, new AgentPrincipal(a.id(), a.ownerId(), a.teamId())).positions())
                .isEqualTo(positions);
    }

    @Test
    void migrationPreservesExistingHistoryAndBackfillsDeterministicSequence() throws Exception {
        // 운영 스키마를 건드리지 않고 같은 임시 DB의 독립 스키마에서 V6 → V7 승격을 검증한다.
        String schema = "sync_migration_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("6").load().migrate();
        try (Connection connection = dataSource.getConnection()) {
            connection.setSchema(schema);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO tasks (request_key, team_id, requester_agent_id, requester_user_id,
                            receiver_agent_id, prompt) VALUES ('legacy', 1, 1, 1, 2, '기존 요청')
                        """);
                statement.executeUpdate("""
                        INSERT INTO task_events (task_id, event_type, content, created_at) VALUES
                        (1, 'PROGRESS', '나중 시각', TIMESTAMP '2026-11-03 02:00:00'),
                        (1, 'CREATED', '동일 시각 첫 이벤트', TIMESTAMP '2026-11-03 01:00:00'),
                        (1, 'ACK_RECEIVED', '동일 시각 두 번째', TIMESTAMP '2026-11-03 01:00:00')
                        """);
            } finally {
                connection.setSchema("public");
            }
        }
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
        try (Connection connection = dataSource.getConnection()) {
            connection.setSchema(schema);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT event_type, content, sequence, author_agent_id, question_event_id, request_key FROM task_events ORDER BY sequence")) {
                for (String expected : List.of("CREATED", "ACK_RECEIVED", "PROGRESS")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("event_type")).isEqualTo(expected);
                    assertThat(rows.getString("content")).isNotBlank();
                    assertThat(rows.getObject("author_agent_id")).isNull();
                    assertThat(rows.getObject("question_event_id")).isNull();
                    assertThat(rows.getObject("request_key")).isNull();
                }
                assertThat(rows.next()).isFalse();
            } finally {
                connection.setSchema("public");
            }
        }
    }

    private long event(long taskId, TestAgent author, String type) {
        Long questionId = null;
        if ("ANSWER".equals(type)) {
            var task = tasks.findById(taskId).orElseThrow();
            long questionAuthor = task.getRequesterAgentId() == author.id()
                    ? task.getReceiverAgentId() : task.getRequesterAgentId();
            var questions = jdbc.queryForList("""
                    SELECT id FROM task_events WHERE task_id = ? AND author_agent_id = ?
                      AND event_type = 'QUESTION' ORDER BY sequence LIMIT 1
                    """, Long.class, taskId, questionAuthor);
            questionId = questions.isEmpty() ? events.saveAndFlush(TaskEvent.of(taskId, TaskEventType.QUESTION,
                    "답변 대상 질문", questionAuthor, null, UUID.randomUUID().toString())).getId() : questions.getFirst();
        }
        return events.saveAndFlush(TaskEvent.of(taskId, TaskEventType.valueOf(type), "본문", author.id(),
                questionId, UUID.randomUUID().toString())).getId();
    }

    private long insert(Connection connection, long taskId, long author, String type) throws Exception {
        try (var statement = connection.prepareStatement("""
                INSERT INTO task_events (task_id, author_agent_id, event_type, content, request_key)
                VALUES (?, ?, ?, '본문', ?) RETURNING id
                """)) {
            statement.setLong(1, taskId);
            statement.setLong(2, author);
            statement.setString(3, type);
            statement.setString(4, UUID.randomUUID().toString());
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private long task(TestAgent requester, TestAgent receiver) {
        return tasks.saveAndFlush(Task.create(UUID.randomUUID().toString(), requester.teamId(), requester.id(),
                requester.ownerId(), receiver.id(), "동기화 테스트 작업")).getId();
    }

    private TestAgent agent(Team team, User owner, String name) {
        String token = UUID.randomUUID().toString();
        Agent entity = Agent.create(team.getId(), owner.getId(), name, "동기화 테스트", ToolType.CODEX, "sync-test");
        entity.issueConnectionToken(AgentTokenHash.sha256(token));
        entity = agents.saveAndFlush(entity);
        return new TestAgent(entity.getId(), team.getId(), owner.getId(), token);
    }

    private String baseUrl() {
        return "http://localhost:" + port + "/api/v1/agents/me/task-messages";
    }

    private HttpResponse<String> pull(TestAgent agent, String cursor, String limit) throws Exception {
        String query = "?";
        if (cursor != null) query += "cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8) + "&";
        if (limit != null) query += "limit=" + URLEncoder.encode(limit, StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(URI.create(baseUrl() + query)).GET();
        if (agent != null) request.header("Authorization", "Bearer " + agent.token());
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private JsonNode success(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isTrue();
        return body.path("data");
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }

    private void assertIds(JsonNode data, long... expected) {
        List<Long> actual = new ArrayList<>();
        data.path("items").forEach(item -> actual.add(Long.parseLong(item.path("eventId").asText())));
        assertThat(actual).containsExactly(java.util.Arrays.stream(expected).boxed().toArray(Long[]::new));
    }

    private record TestAgent(long id, long teamId, long ownerId, String token) { }
}
