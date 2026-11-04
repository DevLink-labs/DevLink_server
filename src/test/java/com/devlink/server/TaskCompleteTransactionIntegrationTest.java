package com.devlink.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.devlink.server.common.AgentTokenHash;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.domain.outbox.OutboxEvent;
import com.devlink.server.domain.outbox.OutboxStatus;
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
import com.devlink.server.dto.TaskStatusChangedMessage;
import com.devlink.server.dto.task.TaskCompleteRequest;
import com.devlink.server.dto.task.TaskFileRequest;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.OutboxEventRepository;
import com.devlink.server.repository.TaskEventRepository;
import com.devlink.server.repository.TaskFileRepository;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TeamMemberRepository;
import com.devlink.server.repository.TeamRepository;
import com.devlink.server.repository.UserRepository;
import com.devlink.server.service.TaskCompleteService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Query;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class TaskCompleteTransactionIntegrationTest extends AbstractIntegrationTest {
    @LocalServerPort private int port;
    @MockitoSpyBean private TaskRepository tasks;
    @MockitoSpyBean private TaskFileRepository files;
    @MockitoSpyBean private TaskEventRepository events;
    @MockitoSpyBean private OutboxEventRepository outbox;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private TeamMemberRepository members;
    @Autowired private AgentRepository agents;
    @Autowired private TaskCompleteService service;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    private Long taskId;
    private long originalVersion;
    private AgentPrincipal principal;
    private String receiverToken;
    private DatabaseState before;

    @BeforeEach
    void prepareCommittedData() {
        // 테스트 자체를 @Transactional로 감싸지 않고 실제 서비스의 커밋·롤백을 관찰한다.
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            String unique = UUID.randomUUID().toString();
            User user = users.save(User.create(unique + "@transaction.test", "결과 트랜잭션 테스트", null, "hash"));
            Team team = teams.save(Team.create("트랜잭션 테스트 팀", unique));
            members.save(TeamMember.create(team.getId(), user.getId(), MemberRole.OWNER));
            Agent requester = agents.saveAndFlush(Agent.create(team.getId(), user.getId(),
                    "요청 Agent", null, ToolType.CODEX, "transaction-test"));
            Agent receiver = Agent.create(team.getId(), user.getId(),
                    "수신 Agent", null, ToolType.CODEX, "transaction-test");
            byte[] tokenBytes = new byte[32];
            new SecureRandom().nextBytes(tokenBytes);
            receiverToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
            receiver.issueConnectionToken(AgentTokenHash.sha256(receiverToken));
            receiver = agents.saveAndFlush(receiver);
            principal = new AgentPrincipal(receiver.getId(), user.getId(), team.getId());

            Task task = tasks.saveAndFlush(Task.create(unique, team.getId(), requester.getId(),
                    user.getId(), receiver.getId(), "트랜잭션 검증 요청"));
            taskId = task.getId();
            events.saveAndFlush(TaskEvent.of(taskId, TaskEventType.CREATED, "기존 생성 이력"));
            task.accept(receiver.getId());
            tasks.flush();
            originalVersion = task.getVersion();
            events.saveAndFlush(TaskEvent.of(taskId, TaskEventType.STATUS_CHANGED, "PENDING → IN_PROGRESS: 작업 수락"));
            files.saveAndFlush(TaskFile.of(taskId, "src/existing.java", ChangeType.MODIFIED, "기존 변경 파일"));

            UUID id = UUID.randomUUID();
            outbox.saveAndFlush(OutboxEvent.create(id, TaskStatusChangedMessage.TYPE, "TASK", taskId.toString(),
                    TaskStatusChangedMessage.DESTINATION, "TASK:" + taskId,
                    json.valueToTree(TaskStatusChangedMessage.from(id, task, TaskStatus.PENDING))));
        });
        before = snapshot();
        assertThat(before.files()).hasSize(1);
        assertThat(before.events()).hasSize(2);
        assertThat(before.outbox()).hasSize(1);
    }

    @Test
    void fileConstraintFailureRollsBackAlreadyFlushedTaskAndFirstFile() throws Exception {
        AtomicBoolean partialWriteObserved = new AtomicBoolean();
        AtomicReference<String> sqlState = new AtomicReference<>();
        doAnswer(call -> {
            Iterable<TaskFile> requested = call.getArgument(0);
            List<TaskFile> candidates = new ArrayList<>();
            requested.forEach(candidates::add);
            assertThat(candidates).hasSize(2);
            entityManager.persist(candidates.getFirst());
            entityManager.flush();
            assertFlushedCompletion(1, 0, 0);
            partialWriteObserved.set(true);
            failWithConstraint("""
                    INSERT INTO task_files (task_id, relative_path, change_type)
                    VALUES (:taskId, NULL, 'MODIFIED')
                    """, Map.of("taskId", taskId), sqlState);
            return candidates;
        }).when(files).saveAll(any());
        ObjectNode body = completionBody("파일 저장 실패 검증", "src/first.java");
        body.withArray("taskFiles").addObject().put("relativePath", "src/second.java").put("changeType", "ADDED");

        assertError(send("POST", completePath(), body), 500, "INTERNAL_SERVER_ERROR");

        assertThat(partialWriteObserved).isTrue();
        assertThat(sqlState.get()).isEqualTo("23502");
        assertRolledBack();
    }

    @Test
    void historyConstraintFailureRollsBackTaskAndSavedFiles() throws Exception {
        AtomicBoolean partialWriteObserved = new AtomicBoolean();
        AtomicReference<String> sqlState = new AtomicReference<>();
        doAnswer(call -> {
            entityManager.flush();
            assertFlushedCompletion(1, 0, 0);
            partialWriteObserved.set(true);
            failWithConstraint("""
                    INSERT INTO task_events (task_id, event_type, content)
                    VALUES (:taskId, NULL, '강제 이력 저장 실패')
                    """, Map.of("taskId", taskId), sqlState);
            return call.getArgument(0);
        }).when(events).save(any(TaskEvent.class));

        assertError(send("POST", completePath(), completionBody("이력 저장 실패 검증", "src/history.java")),
                500, "INTERNAL_SERVER_ERROR");

        assertThat(partialWriteObserved).isTrue();
        assertThat(sqlState.get()).isEqualTo("23502");
        assertRolledBack();
    }

    @Test
    void outboxConstraintFailureRollsBackFlushedTaskFilesAndHistory() throws Exception {
        AtomicBoolean partialWriteObserved = new AtomicBoolean();
        AtomicReference<String> sqlState = new AtomicReference<>();
        doAnswer(call -> {
            OutboxEvent candidate = call.getArgument(0);
            assertFlushedCompletion(1, 1, 0);
            partialWriteObserved.set(true);
            failWithConstraint("""
                    INSERT INTO outbox_events
                        (outbox_id, event_type, aggregate_type, aggregate_id, destination, message)
                    VALUES (:id, 'TASK_COMPLETED', 'TASK', :taskId, NULL, '{}'::jsonb)
                    """, Map.of("id", candidate.getOutboxId(), "taskId", taskId.toString()), sqlState);
            return candidate;
        }).when(outbox).save(any(OutboxEvent.class));

        assertError(send("POST", completePath(), completionBody("Outbox 저장 실패 검증", "src/outbox.java")),
                500, "INTERNAL_SERVER_ERROR");

        assertThat(partialWriteObserved).isTrue();
        assertThat(sqlState.get()).isEqualTo("23502");
        assertRolledBack();
    }

    @Test
    void beforeCommitFailureRollsBackEvenPersistedOutboxAndReturnsError() throws Exception {
        AtomicBoolean beforeCommitObserved = new AtomicBoolean();
        doAnswer(call -> {
            OutboxEvent candidate = call.getArgument(0);
            entityManager.persist(candidate);
            entityManager.flush();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    assertFlushedCompletion(1, 1, 1);
                    beforeCommitObserved.set(true);
                    throw new IllegalStateException("INJECTED_BEFORE_COMMIT_FAILURE");
                }
            });
            return candidate;
        }).when(outbox).save(any(OutboxEvent.class));

        assertError(send("POST", completePath(), completionBody("커밋 직전 실패 검증", "src/before-commit.java")),
                500, "INTERNAL_SERVER_ERROR");

        assertThat(beforeCommitObserved).isTrue();
        assertRolledBack();
    }

    @Test
    void deferredDatabaseConstraintAtCommitRollsBackEntireCompletion() throws Exception {
        AtomicBoolean deferredWritesAccepted = new AtomicBoolean();
        AtomicBoolean beforeCommitObserved = new AtomicBoolean();
        doAnswer(call -> {
            OutboxEvent candidate = call.getArgument(0);
            entityManager.persist(candidate);
            entityManager.flush();
            assertFlushedCompletion(1, 1, 1);
            // TEMP 테이블은 테스트 연결에만 존재하며, 제약 검사는 실제 DB 커밋까지 지연한다.
            entityManager.createNativeQuery("""
                    CREATE TEMP TABLE completion_commit_guard (
                        value INTEGER UNIQUE DEFERRABLE INITIALLY DEFERRED
                    ) ON COMMIT DROP
                    """).executeUpdate();
            entityManager.createNativeQuery("INSERT INTO completion_commit_guard VALUES (1), (1)").executeUpdate();
            deferredWritesAccepted.set(true);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    beforeCommitObserved.set(true);
                }
            });
            return candidate;
        }).when(outbox).save(any(OutboxEvent.class));

        assertError(send("POST", completePath(), completionBody("DB 커밋 실패 검증", "src/commit.java")),
                500, "INTERNAL_SERVER_ERROR");

        assertThat(deferredWritesAccepted).isTrue();
        assertThat(beforeCommitObserved).isTrue();
        assertRolledBack();
    }

    @Test
    void callerTransactionFailureAlsoRollsBackCompletionAndOutbox() {
        AtomicBoolean completedInsideCaller = new AtomicBoolean();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var response = service.complete(taskId, principal, new TaskCompleteRequest(originalVersion,
                    "상위 트랜잭션 롤백 검증", List.of(new TaskFileRequest("src/caller.java", ChangeType.ADDED, null))));
            assertThat(response.version()).isEqualTo(originalVersion + 1);
            entityManager.flush();
            assertFlushedCompletion(1, 1, 1);
            completedInsideCaller.set(true);
            throw new IllegalStateException("INJECTED_CALLER_TRANSACTION_FAILURE");
        })).isInstanceOf(IllegalStateException.class).hasMessage("INJECTED_CALLER_TRANSACTION_FAILURE");

        assertThat(completedInsideCaller).isTrue();
        assertRolledBack();
    }

    @ParameterizedTest
    @ValueSource(strings = {"completion", "failure"})
    void concurrentRequestsCommitOnlyOneResult(String opponent) throws Exception {
        // 두 요청이 모두 잠금 조회 직전에 도달한 뒤 잠금을 시도한다. 먼저 잠근 쪽이 커밋하면
        // 나중 쪽은 갱신된 version을 읽어 TASK_VERSION_CONFLICT로 거절된다.
        CountDownLatch bothLoaded = new CountDownLatch(2);
        AtomicInteger reads = new AtomicInteger();
        doAnswer(call -> {
            reads.incrementAndGet();
            bothLoaded.countDown();
            if (!bothLoaded.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Both requests must reach the locked read");
            }
            return Optional.ofNullable(entityManager.find(Task.class, taskId, LockModeType.PESSIMISTIC_WRITE));
        }).when(tasks).findByIdForUpdate(eq(taskId));

        ObjectNode firstBody = completionBody("동시 완료 A", "src/winner-a.java");
        ObjectNode secondBody;
        String secondMethod;
        String secondPath;
        if ("completion".equals(opponent)) {
            secondBody = completionBody("동시 완료 B", "src/winner-b.java");
            secondMethod = "POST";
            secondPath = completePath();
        } else {
            secondBody = json.createObjectNode().put("status", "FAILED").put("version", originalVersion)
                    .put("failureReason", "동시 실패 B").put("partiallyModified", true);
            secondMethod = "PATCH";
            secondPath = "/api/v1/tasks/" + taskId + "/status";
        }

        HttpResponse<String> first;
        HttpResponse<String> second;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> send("POST", completePath(), firstBody));
            var b = executor.submit(() -> send(secondMethod, secondPath, secondBody));
            first = a.get(30, TimeUnit.SECONDS);
            second = b.get(30, TimeUnit.SECONDS);
        }
        assertThat(reads).hasValue(2);
        assertThat(List.of(first.statusCode(), second.statusCode())).containsExactlyInAnyOrder(200, 409);
        HttpResponse<String> winner = first.statusCode() == 200 ? first : second;
        HttpResponse<String> loser = first.statusCode() == 409 ? first : second;
        assertError(loser, 409, "TASK_VERSION_CONFLICT");
        JsonNode response = assertSuccess(winner);
        boolean completed = "COMPLETED".equals(response.path("status").asText());
        assertThat(response.path("version").asLong()).isEqualTo(originalVersion + 1);

        Map<String, Object> task = taskRow();
        assertThat(task.get("status")).isEqualTo(completed ? "COMPLETED" : "FAILED");
        assertThat(((Number) task.get("version")).longValue()).isEqualTo(originalVersion + 1);
        assertThat(task.get("completed_at")).isNotNull();
        assertThat(task.get("started_at")).isEqualTo(before.task().get("started_at"));
        String expectedResult = first.statusCode() == 200 ? "동시 완료 A"
                : completed ? "동시 완료 B" : "동시 실패 B";
        assertThat(task.get("result_text")).isEqualTo(expectedResult);
        assertThat(task.get("partially_modified")).isEqualTo(!completed);

        List<Map<String, Object>> savedFiles = fileRows();
        assertThat(savedFiles).containsAll(before.files()).hasSize(before.files().size() + (completed ? 1 : 0));
        if (completed) {
            String winnerPath = first.statusCode() == 200 ? "src/winner-a.java" : "src/winner-b.java";
            assertThat(savedFiles.stream().filter(row -> !before.files().contains(row))
                    .map(row -> row.get("relative_path")).toList()).containsExactly(winnerPath);
        }
        assertThat(eventRows()).containsAll(before.events()).hasSize(before.events().size() + 1);
        assertThat(outboxRows()).containsAll(before.outbox()).hasSize(before.outbox().size() + 1);
        assertThat(outbox.findAll().stream().filter(event -> taskId.toString().equals(event.getAggregateId())
                        && "TASK".equals(event.getAggregateType())
                        && event.getMessage().path("version").asLong() == originalVersion + 1).toList())
                .singleElement().satisfies(event -> {
                    assertThat(event.getEventType()).isEqualTo(completed ? "TASK_COMPLETED" : "TASK_STATUS_CHANGED");
                    assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
                    assertThat(event.getMessage().path("payload").path("status").asText())
                            .isEqualTo(completed ? "COMPLETED" : "FAILED");
                    if (completed) {
                        assertThat(event.getMessage().path("payload").path("result_text").asText()).isEqualTo(expectedResult);
                    }
                });
    }

    private void assertFlushedCompletion(int additionalFiles, int additionalEvents, int additionalOutbox) {
        Map<String, Object> task = taskRow();
        assertThat(task.get("status")).isEqualTo("COMPLETED");
        assertThat(((Number) task.get("version")).longValue()).isEqualTo(originalVersion + 1);
        assertThat(fileRows()).hasSize(before.files().size() + additionalFiles);
        assertThat(eventRows()).hasSize(before.events().size() + additionalEvents);
        assertThat(outboxRows()).hasSize(before.outbox().size() + additionalOutbox);
    }

    private void failWithConstraint(String sql, Map<String, Object> parameters, AtomicReference<String> sqlState) {
        Query query = entityManager.createNativeQuery(sql);
        parameters.forEach(query::setParameter);
        try {
            query.executeUpdate();
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException databaseFailure) {
                    sqlState.set(databaseFailure.getSQLState());
                    break;
                }
            }
            throw failure;
        }
        throw new AssertionError("Database constraint violation was expected");
    }

    private void assertRolledBack() {
        assertThat(snapshot()).isEqualTo(before);
        assertThat(taskRow().get("status")).isEqualTo("IN_PROGRESS");
        assertThat(((Number) taskRow().get("version")).longValue()).isEqualTo(originalVersion);
    }

    private ObjectNode completionBody(String result, String path) {
        ObjectNode body = json.createObjectNode().put("version", originalVersion).put("resultText", result);
        body.putArray("taskFiles").addObject().put("relativePath", path)
                .put("changeType", "ADDED").put("diffText", "+ transaction test");
        return body;
    }

    private String completePath() {
        return "/api/v1/tasks/" + taskId + "/complete";
    }

    private HttpResponse<String> send(String method, String path, JsonNode body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(25)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + receiverToken)
                .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
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

    private DatabaseState snapshot() {
        return new DatabaseState(taskRow(), fileRows(), eventRows(), outboxRows());
    }

    private Map<String, Object> taskRow() {
        return jdbc.queryForMap("select * from tasks where id = ?", taskId);
    }

    private List<Map<String, Object>> fileRows() {
        return jdbc.queryForList("select * from task_files where task_id = ? order by id", taskId);
    }

    private List<Map<String, Object>> eventRows() {
        return jdbc.queryForList("select * from task_events where task_id = ? order by id", taskId);
    }

    private List<Map<String, Object>> outboxRows() {
        return jdbc.queryForList("""
                select * from outbox_events
                where aggregate_type = 'TASK' and aggregate_id = ? order by outbox_id
                """, taskId.toString());
    }

    private record DatabaseState(Map<String, Object> task, List<Map<String, Object>> files,
            List<Map<String, Object>> events, List<Map<String, Object>> outbox) { }
}
