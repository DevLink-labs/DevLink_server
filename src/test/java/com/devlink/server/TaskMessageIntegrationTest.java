package com.devlink.server;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.teammember.TeamMember;
import com.devlink.server.domain.user.User;
import com.devlink.server.dto.task.TaskAnswerRequest;
import com.devlink.server.dto.task.TaskCompleteRequest;
import com.devlink.server.dto.task.TaskQuestionRequest;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskEventRepository;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TeamMemberRepository;
import com.devlink.server.repository.TeamRepository;
import com.devlink.server.repository.UserRepository;
import com.devlink.server.service.AgentTokenService;
import com.devlink.server.service.TaskMessageOutboxWriter;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

class TaskMessageIntegrationTest extends AbstractIntegrationTest {
    @Autowired TestRestTemplate rest;
    @Autowired UserRepository users;
    @Autowired TeamRepository teams;
    @Autowired TeamMemberRepository members;
    @Autowired AgentRepository agents;
    @Autowired AgentTokenService tokens;
    @MockitoSpyBean TaskRepository tasks;
    @Autowired TaskEventRepository events;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager entityManager;
    @MockitoSpyBean TaskMessageOutboxWriter outbox;

    private Long teamId;
    private Long ownerId;
    private Long requesterId;
    private Long receiverId;
    private Long taskId;
    private String requesterToken;
    private String receiverToken;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString();
        ownerId = users.save(User.create(unique + "@test.com", "Owner", null, "hash")).getId();
        teamId = teams.save(Team.create("Messages", unique)).getId();
        members.save(TeamMember.create(teamId, ownerId, MemberRole.OWNER));
        requesterId = createAgent("Requester");
        receiverId = createAgent("Receiver");
        requesterToken = tokens.issue(ownerId, requesterId).token();
        receiverToken = tokens.issue(ownerId, receiverId).token();
        taskId = createTask(receiverId, true);
    }

    @Test
    void registersQuestionAndMultipleAnswersWithOppositeRecipientsAndKeepsTaskUnchanged() {
        Task before = tasks.findById(taskId).orElseThrow();
        String message = "확인해주세요.\n  code";
        JsonNode question = success(post(taskId, "questions", requesterToken,
                new TaskQuestionRequest("q", message)), HttpStatus.CREATED);
        String questionId = question.path("eventId").asText();
        assertThat(question.path("eventId").isTextual()).isTrue();
        assertThat(question.path("sequence").textValue()).isEqualTo("1");
        assertThat(question.path("eventType").asText()).isEqualTo("QUESTION");
        assertThat(question.path("message").asText()).isEqualTo(message);
        assertThat(question.path("questionEventId").isNull()).isTrue();
        assertThat(question.path("replayed").asBoolean()).isFalse();
        assertThat(question.path("authorAgent").path("agentId").asLong()).isEqualTo(requesterId);
        assertThat(question.path("authorAgent").path("name").asText()).isEqualTo("Requester");

        JsonNode answer = success(post(taskId, "answers", receiverToken,
                new TaskAnswerRequest("a", "확인했습니다.", questionId)), HttpStatus.CREATED);
        assertThat(answer.path("sequence").textValue()).isEqualTo("2");
        assertThat(answer.path("eventType").asText()).isEqualTo("ANSWER");
        assertThat(answer.path("questionEventId").textValue()).isEqualTo(questionId);
        JsonNode followUp = success(post(taskId, "answers", receiverToken,
                new TaskAnswerRequest("a2", "추가 확인 결과입니다.", questionId)), HttpStatus.CREATED);
        assertThat(followUp.path("sequence").textValue()).isEqualTo("3");

        assertOutbox("TASK_QUESTION_CREATED", questionId, receiverId);
        assertOutbox("TASK_ANSWER_CREATED", answer.path("eventId").asText(), requesterId);
        assertOutbox("TASK_ANSWER_CREATED", followUp.path("eventId").asText(), requesterId);
        assertCounts(3);
        Task after = tasks.findById(taskId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getStartedAt()).isEqualTo(before.getStartedAt());
        assertThat(after.getCompletedAt()).isEqualTo(before.getCompletedAt());
    }

    @Test
    void replaysAfterCompletionAndRejectsKeyReuseAcrossTypesOrQuestionReferences() {
        var questionRequest = new TaskQuestionRequest("q", "Question");
        JsonNode question = success(post(taskId, "questions", requesterToken, questionRequest), HttpStatus.CREATED);
        String questionId = question.path("eventId").asText();
        JsonNode secondQuestion = success(post(taskId, "questions", requesterToken,
                new TaskQuestionRequest("q2", "Another question")), HttpStatus.CREATED);
        var answerRequest = new TaskAnswerRequest("a", "Answer", questionId);
        JsonNode answer = success(post(taskId, "answers", receiverToken, answerRequest), HttpStatus.CREATED);
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                tasks.findByIdForUpdate(taskId).orElseThrow().complete(receiverId, "Done"));

        assertReplay(post(taskId, "questions", requesterToken, questionRequest), question);
        assertReplay(post(taskId, "answers", receiverToken, answerRequest), answer);
        assertError(post(taskId, "questions", receiverToken, new TaskQuestionRequest("a", "Answer")),
                HttpStatus.CONFLICT, "REQUEST_KEY_CONFLICT");
        assertError(post(taskId, "answers", receiverToken,
                new TaskAnswerRequest("a", "Answer", secondQuestion.path("eventId").asText())),
                HttpStatus.CONFLICT, "REQUEST_KEY_CONFLICT");
        assertError(post(taskId, "answers", receiverToken, new TaskAnswerRequest("new", "Answer", questionId)),
                HttpStatus.CONFLICT, "TASK_STATE_CONFLICT");
        assertCounts(3);
    }

    @Test
    void rejectsMissingCrossTaskAndNonQuestionReferencesAndSelfAnswers() {
        String questionId = success(post(taskId, "questions", requesterToken,
                new TaskQuestionRequest("q", "Question")), HttpStatus.CREATED).path("eventId").asText();
        String answerId = success(post(taskId, "answers", receiverToken,
                new TaskAnswerRequest("a", "Answer", questionId)), HttpStatus.CREATED).path("eventId").asText();
        String crossTaskId = success(post(createTask(receiverId, true), "questions", requesterToken,
                new TaskQuestionRequest("q", "Other task")), HttpStatus.CREATED).path("eventId").asText();
        for (String invalid : new String[] {Long.toString(Long.MAX_VALUE), answerId, crossTaskId}) {
            assertError(post(taskId, "answers", receiverToken, new TaskAnswerRequest("invalid", "Answer", invalid)),
                    HttpStatus.BAD_REQUEST, "INVALID_QUESTION_REFERENCE");
        }
        assertError(post(taskId, "answers", requesterToken, new TaskAnswerRequest("self", "Answer", questionId)),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertCounts(2);
    }

    @Test
    void enforcesAuthenticationParticipationStateAndSelfAssignment() {
        var request = new TaskQuestionRequest("q", "Question");
        assertError(post(taskId, "questions", null, request),
                HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED");
        String outsiderToken = tokens.issue(ownerId, createAgent("Other Agent")).token();
        assertError(post(taskId, "questions", outsiderToken, request), HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        assertError(post(createTask(receiverId, false), "questions", requesterToken, request),
                HttpStatus.CONFLICT, "TASK_STATE_CONFLICT");
        Long selfAssigned = createTask(requesterId, true);
        assertError(post(selfAssigned, "questions", requesterToken, request),
                HttpStatus.CONFLICT, "TASK_CONVERSATION_UNAVAILABLE");
        assertError(post(selfAssigned, "answers", requesterToken, new TaskAnswerRequest("a", "Answer", "1")),
                HttpStatus.CONFLICT, "TASK_CONVERSATION_UNAVAILABLE");
        assertCounts(0);
    }

    @Test
    void validatesBodyLengthByCodePointAndRequiresValidQuestionId() {
        String limit = "😀".repeat(8_000);
        assertError(post(taskId, "questions", requesterToken, new TaskQuestionRequest("q", " \n ")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(post(taskId, "questions", requesterToken, new TaskQuestionRequest("q", limit + "x")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertError(post(taskId, "answers", receiverToken,
                new TaskAnswerRequest("a", "Answer", "9223372036854775808")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        JsonNode question = success(post(taskId, "questions", requesterToken,
                new TaskQuestionRequest("q", limit)), HttpStatus.CREATED);
        assertThat(question.path("message").asText()).isEqualTo(limit);
        assertCounts(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"questions", "answers"})
    void completionRacingWithMessageNeverStoresMessageAfterTermination(String endpoint) throws Exception {
        for (int round = 0; round < 10; round++) {
            Long raceTaskId = createTask(receiverId, true);
            long version = tasks.findById(raceTaskId).orElseThrow().getVersion();
            // 답변 경합은 수신자가 먼저 남긴 질문에 요청자가 답하는 동안 수신자가 완료를 보낸다.
            String questionId = "answers".equals(endpoint) ? success(post(raceTaskId, "questions", receiverToken,
                    new TaskQuestionRequest("q", "Question")), HttpStatus.CREATED).path("eventId").asText() : null;
            Object body = questionId == null ? new TaskQuestionRequest("race", "Still in progress?")
                    : new TaskAnswerRequest("race", "Answer", questionId);
            String author = questionId == null ? receiverToken : requesterToken;
            int existing = questionId == null ? 0 : 1;
            CountDownLatch bothLoaded = releaseTaskReadsTogether(raceTaskId);

            ResponseEntity<JsonNode> sent;
            ResponseEntity<JsonNode> completed;
            try (var executor = Executors.newFixedThreadPool(2)) {
                var sending = executor.submit(() -> post(raceTaskId, endpoint, author, body));
                var completing = executor.submit(() -> complete(raceTaskId, version));
                sent = sending.get(30, TimeUnit.SECONDS);
                completed = completing.get(30, TimeUnit.SECONDS);
            }

            assertThat(bothLoaded.getCount()).as("round %d: both requests must load the task", round).isZero();
            assertThat(success(completed, HttpStatus.OK).path("status").asText()).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, raceTaskId))
                    .isEqualTo("COMPLETED");
            List<TaskEvent> rows = events.findByTaskIdOrderBySequenceAsc(raceTaskId);
            long terminal = rows.stream().filter(event -> event.getEventType() == TaskEventType.STATUS_CHANGED)
                    .mapToLong(TaskEvent::getSequence).max().orElseThrow();
            List<TaskEvent> conversation = rows.stream().filter(event ->
                    event.getEventType() == TaskEventType.QUESTION || event.getEventType() == TaskEventType.ANSWER)
                    .toList();
            assertThat(conversation).as("round %d: %s", round, sent.getBody())
                    .allSatisfy(event -> assertThat(event.getSequence()).isLessThan(terminal));
            if (sent.getStatusCode() == HttpStatus.CREATED) {
                assertThat(success(sent, HttpStatus.CREATED).path("sequence").asLong()).isLessThan(terminal);
                assertThat(conversation).hasSize(existing + 1);
            } else {
                assertError(sent, HttpStatus.CONFLICT, "TASK_STATE_CONFLICT");
                assertThat(conversation).hasSize(existing);
            }
            assertThat(messageOutboxCount(raceTaskId)).isEqualTo(conversation.size());
        }
    }

    @Test
    void concurrentSameRequestKeyStoresOneQuestionAndReplaysItToTheOther() throws Exception {
        var request = new TaskQuestionRequest("dup", "Question");
        CountDownLatch bothLoaded = releaseTaskReadsTogether(taskId);

        ResponseEntity<JsonNode> first;
        ResponseEntity<JsonNode> second;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> post(taskId, "questions", requesterToken, request));
            var b = executor.submit(() -> post(taskId, "questions", requesterToken, request));
            first = a.get(30, TimeUnit.SECONDS);
            second = b.get(30, TimeUnit.SECONDS);
        }

        assertThat(bothLoaded.getCount()).isZero();
        assertThat(List.of(first.getStatusCode(), second.getStatusCode()))
                .as("responses %s / %s", first.getBody(), second.getBody())
                .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.OK);
        ResponseEntity<JsonNode> created = first.getStatusCode() == HttpStatus.CREATED ? first : second;
        ResponseEntity<JsonNode> replayed = created == first ? second : first;
        assertReplay(replayed, success(created, HttpStatus.CREATED));
        assertCounts(1);
    }

    @Test
    void rollsBackQuestionWhenOutboxWriteFails() {
        doThrow(new IllegalStateException("Outbox write failed")).when(outbox).enqueue(any(), eq(receiverId));
        assertError(post(taskId, "questions", requesterToken, new TaskQuestionRequest("q", "Question")),
                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR");
        assertCounts(0);
    }

    private Long createAgent(String name) {
        return agents.save(Agent.create(teamId, ownerId, name, null, ToolType.CODEX, "server")).getId();
    }

    private Long createTask(Long assignee, boolean inProgress) {
        return new TransactionTemplate(transactions).execute(tx -> {
            Task task = tasks.save(Task.create(UUID.randomUUID().toString(), teamId,
                    requesterId, ownerId, assignee, "Prompt"));
            if (inProgress) {
                task.accept(assignee);
            }
            return task.getId();
        });
    }

    /**
     * 두 요청이 모두 Task를 읽는 지점에 도달한 뒤에야 실제 조회(와 잠금)를 진행하게 한다.
     * 리포지토리 스파이는 실제 메서드를 부를 수 없으므로 요청 스레드의 EntityManager로 같은 조회를 수행한다.
     */
    private CountDownLatch releaseTaskReadsTogether(Long id) {
        CountDownLatch bothLoaded = new CountDownLatch(2);
        Answer<Optional<Task>> plain = call -> {
            awaitBoth(bothLoaded);
            return Optional.ofNullable(entityManager.find(Task.class, id));
        };
        Answer<Optional<Task>> locked = call -> {
            awaitBoth(bothLoaded);
            return Optional.ofNullable(entityManager.find(Task.class, id, LockModeType.PESSIMISTIC_WRITE));
        };
        doAnswer(plain).when(tasks).findById(eq(id));
        doAnswer(locked).when(tasks).findByIdForUpdate(eq(id));
        return bothLoaded;
    }

    private static void awaitBoth(CountDownLatch bothLoaded) throws InterruptedException {
        bothLoaded.countDown();
        if (!bothLoaded.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Both requests must reach the task read");
        }
    }

    private ResponseEntity<JsonNode> complete(Long id, long version) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(receiverToken);
        return rest.postForEntity("/api/v1/tasks/{taskId}/complete",
                new HttpEntity<>(new TaskCompleteRequest(version, "Done", List.of()), headers), JsonNode.class, id);
    }

    private long messageOutboxCount(Long id) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events WHERE aggregate_type = 'TASK' AND aggregate_id = ?
                    AND event_type IN ('TASK_QUESTION_CREATED', 'TASK_ANSWER_CREATED')
                """, Long.class, id.toString());
    }

    private ResponseEntity<JsonNode> post(Long id, String endpoint, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.postForEntity("/api/v1/tasks/{taskId}/{endpoint}", new HttpEntity<>(body, headers),
                JsonNode.class, id, endpoint);
    }

    private JsonNode success(ResponseEntity<JsonNode> response, HttpStatus status) {
        assertThat(response.getStatusCode()).as("response %s", response.getBody()).isEqualTo(status);
        assertThat(response.getBody().path("success").asBoolean()).isTrue();
        return response.getBody().path("data");
    }

    private void assertError(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).as("response %s", response.getBody()).isEqualTo(status);
        assertThat(response.getBody().path("success").asBoolean()).isFalse();
        assertThat(response.getBody().path("code").asText()).isEqualTo(code);
    }

    private void assertReplay(ResponseEntity<JsonNode> response, JsonNode original) {
        JsonNode replay = success(response, HttpStatus.OK);
        assertThat(replay.path("replayed").asBoolean()).isTrue();
        assertThat(replay.path("eventId")).isEqualTo(original.path("eventId"));
        assertThat(replay.path("sequence")).isEqualTo(original.path("sequence"));
        assertThat(replay.path("createdAt")).isEqualTo(original.path("createdAt"));
    }

    private void assertOutbox(String type, String eventId, Long recipientId) {
        assertThat(jdbc.queryForList("""
                SELECT message ->> 'receiver_agent_id' FROM outbox_events
                WHERE aggregate_type = 'TASK' AND aggregate_id = ? AND event_type = ?
                    AND message -> 'payload' ->> 'task_event_id' = ?
                """, String.class, taskId.toString(), type, eventId)).containsExactly(recipientId.toString());
    }

    private void assertCounts(int expected) {
        assertThat(events.findByTaskIdOrderBySequenceAsc(taskId)).hasSize(expected);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events WHERE aggregate_type = 'TASK' AND aggregate_id = ?
                """, Long.class, taskId.toString())).isEqualTo((long) expected);
    }
}
