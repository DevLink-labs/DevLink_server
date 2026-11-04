package com.devlink.server;

import java.util.UUID;
import java.util.List;
import com.devlink.server.domain.outbox.OutboxEvent;
import com.devlink.server.domain.outbox.OutboxStatus;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.user.User;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.dto.ChangeTaskStatusRequest;
import com.devlink.server.repository.*;
import com.devlink.server.service.TaskStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.LockModeType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TaskStatusIntegrationTest extends AbstractIntegrationTest {
    @Autowired TaskStatusService service;
    @MockitoSpyBean TaskRepository tasks;
    @MockitoSpyBean TaskEventRepository events;
    @MockitoSpyBean OutboxEventRepository outbox;
    @Autowired UserRepository users;
    @Autowired TeamRepository teams;
    @Autowired AgentRepository agents;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired jakarta.persistence.EntityManager entityManager;
    Long taskId;
    Long userId;
    Long agentId;

    @BeforeEach
    void setup() {
        String key = UUID.randomUUID().toString();
        userId = users.save(User.create(key + "@test.com", "Tester", null, "hash")).getId();
        Long teamId = teams.save(Team.create("Status", key)).getId();
        agentId = agents.save(Agent.create(teamId, userId, "Receiver", null, ToolType.CODEX, "server")).getId();
        taskId = tasks.save(Task.create(key, teamId, agentId, userId, agentId, "Prompt")).getId();
    }

    private ChangeTaskStatusRequest request(TaskStatus status, Long version) {
        return new ChangeTaskStatusRequest(status, version,
                status == TaskStatus.REJECTED ? "Cannot access project" : null,
                status == TaskStatus.FAILED ? "Build failed" : null,
                status == TaskStatus.FAILED ? Boolean.TRUE : null);
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"IN_PROGRESS", "REJECTED", "FAILED"})
    void persistsTransitionAndHistoryAndReturnsFlushedVersion(TaskStatus target) {
        long version = 0;
        if (target == TaskStatus.FAILED) {
            version = service.changeStatus(taskId, agentId, request(TaskStatus.IN_PROGRESS, version)).version();
        }
        var response = service.changeStatus(taskId, agentId, request(target, version));
        var saved = tasks.findById(taskId).orElseThrow();
        assertThat(response.status()).isEqualTo(target);
        assertThat(response.version()).isGreaterThan(version).isEqualTo(saved.getVersion());
        assertThat(saved.getDeliveredAt()).isNull();
        if (target == TaskStatus.IN_PROGRESS) {
            assertThat(response.startedAt()).isNotNull();
            assertThat(response.completedAt()).isNull();
        } else {
            assertThat(response.completedAt()).isNotNull();
        }
        if (target == TaskStatus.REJECTED) {
            assertThat(response.rejectionReason()).isEqualTo("Cannot access project");
            assertThat(response.failureReason()).isNull();
            assertThat(response.startedAt()).isNull();
            assertThat(response.partiallyModified()).isFalse();
        }
        if (target == TaskStatus.FAILED) {
            assertThat(response.failureReason()).isEqualTo("Build failed");
            assertThat(response.rejectionReason()).isNull();
            assertThat(response.partiallyModified()).isTrue();
        }
        var history = events.findByTaskIdOrderByCreatedAtAsc(taskId);
        assertThat(history).hasSize(target == TaskStatus.FAILED ? 2 : 1);
        TaskEvent last = history.getLast();
        assertThat(last.getEventType()).isEqualTo(TaskEventType.STATUS_CHANGED);
        assertThat(last.getContent()).startsWith(
                (target == TaskStatus.FAILED ? "IN_PROGRESS" : "PENDING") + " → " + target + ": ");
        assertThat(taskOutbox()).hasSize(target == TaskStatus.FAILED ? 2 : 1);
        var publishedCandidate = taskOutbox().stream()
                .filter(event -> event.getMessage().path("version").asLong() == response.version())
                .findFirst().orElseThrow();
        assertThat(publishedCandidate.getEventType()).isEqualTo("TASK_STATUS_CHANGED");
        assertThat(publishedCandidate.getDestination()).isEqualTo("task-events");
        assertThat(publishedCandidate.getMessageKey()).isEqualTo("TASK:" + taskId);
        assertThat(publishedCandidate.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(publishedCandidate.getAttemptCount()).isZero();
        var message = publishedCandidate.getMessage();
        assertThat(message.size()).isEqualTo(5);
        assertThat(message.path("event_id").asText()).isEqualTo(publishedCandidate.getOutboxId().toString());
        assertThat(message.path("type").asText()).isEqualTo("TASK_STATUS_CHANGED");
        assertThat(message.path("task_id").asLong()).isEqualTo(taskId);
        assertThat(message.path("payload").path("previous_status").asText())
                .isEqualTo(target == TaskStatus.FAILED ? "IN_PROGRESS" : "PENDING");
        assertThat(message.path("payload").path("status").asText()).isEqualTo(target.name());
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"IN_PROGRESS", "REJECTED", "FAILED"})
    void otherAgentCannotChangeTaskEvenWithWrongVersion(TaskStatus target) {
        assertThatThrownBy(() -> service.changeStatus(taskId, Long.MAX_VALUE, request(target, 99L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
        assertUnchanged();
    }

    @Test
    void missingTaskReturnsNotFound() {
        assertThatThrownBy(() -> service.changeStatus(Long.MAX_VALUE, agentId, request(TaskStatus.IN_PROGRESS, 0L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void staleVersionIsRejectedBeforeStateValidation() {
        service.changeStatus(taskId, agentId, request(TaskStatus.REJECTED, 0L));
        assertThatThrownBy(() -> service.changeStatus(taskId, agentId, request(TaskStatus.IN_PROGRESS, 0L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TASK_VERSION_CONFLICT));
        assertThat(events.findByTaskIdOrderByCreatedAtAsc(taskId)).hasSize(1);
        assertThat(taskOutbox()).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"IN_PROGRESS", "REJECTED", "FAILED", "COMPLETED"})
    void cannotAcceptNonPendingTask(TaskStatus initial) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            Task task = tasks.findById(taskId).orElseThrow();
            if (initial == TaskStatus.REJECTED) task.reject(task.getReceiverAgentId(), "No");
            else {
                task.accept(task.getReceiverAgentId());
                if (initial == TaskStatus.FAILED) task.fail(task.getReceiverAgentId(), "Failed", false);
                if (initial == TaskStatus.COMPLETED) task.complete(task.getReceiverAgentId(), "Done");
            }
        });
        Long version = tasks.findById(taskId).orElseThrow().getVersion();
        assertThatThrownBy(() -> service.changeStatus(taskId, agentId, request(TaskStatus.IN_PROGRESS, version)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TASK_STATE_CONFLICT));
        assertThat(events.findByTaskIdOrderByCreatedAtAsc(taskId)).isEmpty();
        assertThat(taskOutbox()).isEmpty();
    }

    @Test
    void pendingTaskCannotFail() {
        assertThatThrownBy(() -> service.changeStatus(taskId, agentId, request(TaskStatus.FAILED, 0L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TASK_STATE_CONFLICT));
    }

    @Test
    void historyFailureRollsBackTask() {
        doThrow(new IllegalStateException("history failed")).when(events).save(any(TaskEvent.class));
        assertThatThrownBy(() -> service.changeStatus(taskId, agentId, request(TaskStatus.IN_PROGRESS, 0L)))
                .isInstanceOf(IllegalStateException.class);
        assertUnchanged();
    }

    @Test
    void outboxInsertFailureRollsBackFlushedTaskAndHistory() {
        doAnswer(call -> {
            OutboxEvent candidate = call.getArgument(0);
            // Exercise a real database constraint failure, after task/history have been flushed.
            entityManager.createNativeQuery("""
                    INSERT INTO outbox_events
                        (outbox_id, event_type, aggregate_type, aggregate_id, destination, message)
                    VALUES (:id, 'TASK_STATUS_CHANGED', 'TASK', :taskId, NULL, '{}'::jsonb)
                    """).setParameter("id", candidate.getOutboxId())
                    .setParameter("taskId", taskId.toString()).executeUpdate();
            return candidate;
        }).when(outbox).save(any(OutboxEvent.class));
        assertThatThrownBy(() -> service.changeStatus(taskId, agentId, request(TaskStatus.IN_PROGRESS, 0L)))
                .isInstanceOf(RuntimeException.class);
        assertUnchanged();
    }

    @Test
    void commitFailureRollsBackTaskAndHistory() {
        doAnswer(call -> {
            TaskEvent saved = call.getArgument(0);
            entityManager.persist(saved);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    throw new IllegalStateException("commit failed");
                }
            });
            return saved;
        }).when(events).save(any(TaskEvent.class));
        assertThatThrownBy(() -> service.changeStatus(taskId, agentId, request(TaskStatus.REJECTED, 0L)))
                .isInstanceOf(IllegalStateException.class);
        assertUnchanged();
    }

    @Test
    void concurrentAcceptAndRejectCommitOnlyOneTransition() throws Exception {
        // 두 요청이 모두 잠금 조회 직전에 도달한 뒤 잠금을 시도한다. 먼저 잠근 쪽이 커밋하면
        // 나중 쪽은 갱신된 version을 읽어 TASK_VERSION_CONFLICT로 거절된다.
        var ready = new CountDownLatch(2);
        doAnswer(call -> {
            ready.countDown();
            if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent read missing");
            return java.util.Optional.ofNullable(
                    entityManager.find(Task.class, taskId, LockModeType.PESSIMISTIC_WRITE));
        }).when(tasks).findByIdForUpdate(taskId);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var accept = executor.submit(() -> service.changeStatus(taskId, agentId, request(TaskStatus.IN_PROGRESS, 0L)));
            var reject = executor.submit(() -> service.changeStatus(taskId, agentId, request(TaskStatus.REJECTED, 0L)));
            int successes = 0;
            int conflicts = 0;
            for (var result : java.util.List.of(accept, reject)) {
                try {
                    assertThat(result.get(20, TimeUnit.SECONDS).version()).isEqualTo(1L);
                    successes++;
                } catch (ExecutionException exception) {
                    assertThat(exception.getCause()).isInstanceOfSatisfying(BusinessException.class,
                            cause -> assertThat(cause.getErrorCode()).isEqualTo(ErrorCode.TASK_VERSION_CONFLICT));
                    conflicts++;
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
        }
        var saved = tasks.findById(taskId).orElseThrow();
        assertThat(saved.getVersion()).isEqualTo(1L);
        assertThat(events.findByTaskIdOrderByCreatedAtAsc(taskId)).singleElement()
                .satisfies(event -> assertThat(event.getContent()).contains(" → " + saved.getStatus() + ": "));
        assertThat(taskOutbox()).singleElement().satisfies(event -> {
            assertThat(event.getMessage().path("version").asLong()).isEqualTo(saved.getVersion());
            assertThat(event.getMessage().path("payload").path("status").asText())
                    .isEqualTo(saved.getStatus().name());
        });
    }

    private List<OutboxEvent> taskOutbox() {
        return outbox.findAll().stream()
                .filter(event -> event.getAggregateType().equals("TASK")
                        && event.getAggregateId().equals(taskId.toString()))
                .toList();
    }

    private void assertUnchanged() {
        var task = tasks.findById(taskId).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.getVersion()).isZero();
        assertThat(task.getStartedAt()).isNull();
        assertThat(task.getCompletedAt()).isNull();
        assertThat(task.getResultText()).isNull();
        assertThat(events.findByTaskIdOrderByCreatedAtAsc(taskId)).isEmpty();
        assertThat(taskOutbox()).isEmpty();
    }
}
