package com.devlink.server;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.ToolType;

import com.devlink.server.domain.task.TaskStatus;

import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.domain.user.User;
import com.devlink.server.dto.CreateTaskRequest;
import com.devlink.server.repository.*;
import com.devlink.server.service.TaskCreationService;
import com.devlink.server.service.TaskRoutingService;
import com.devlink.server.dto.TaskRoutingResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.devlink.server.dto.TaskDeliveryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskCreationIntegrationTest extends AbstractIntegrationTest {
    @Autowired TaskCreationService service;
    @MockitoBean TaskRoutingService routing;

    @MockitoSpyBean TaskRepository tasks;
    @Autowired UserRepository users;
    @Autowired TeamRepository teams;
    @Autowired AgentRepository agents;
    @MockitoSpyBean TaskEventRepository events;
    Long teamId;
    Long userId;
    Long agentId;
    CreateTaskRequest request;

    @BeforeEach
    void setup() {
        String key = UUID.randomUUID().toString();
        userId = users.save(User.create(key + "@test.com", "Tester", null, "hash")).getId();
        teamId = teams.save(Team.create("Test", key)).getId();
        agentId = agents.save(Agent.create(teamId, userId, "Agent", null, ToolType.CODEX, "project")).getId();
        request = new CreateTaskRequest(key, agentId, "Implement feature");
        when(routing.sendTask(anyLong())).thenAnswer(invocation ->
                new TaskRoutingResult(invocation.getArgument(0), TaskRoutingResult.Status.SENT, "event", "connection"));
        when(routing.isAwaitingAck(any())).thenReturn(true);
    }

    @Test
    void routesAfterCreatedCommitThenStoresAckPending() {
        when(routing.sendTask(anyLong())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Long taskId = invocation.getArgument(0);
            assertThat(tasks.findById(taskId)).isPresent();
            assertThat(events.findByTaskIdOrderByCreatedAtAsc(taskId))
                    .extracting(TaskEvent::getEventType).containsExactly(TaskEventType.CREATED);
            assertThat(service.create(teamId, agentId, request).delivery()).isNull();
            return new TaskRoutingResult(taskId, TaskRoutingResult.Status.SENT, "event", "connection");
        });
        var result = service.create(teamId, agentId, request);
        assertThat(result.replayed()).isFalse();
        assertThat(result.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(result.version()).isEqualTo(0L);
        assertThat(result.delivery().status()).isEqualTo(TaskEventType.ACK_PENDING);
        assertThat(tasks.findById(result.taskId()).orElseThrow().getDeliveredAt()).isNull();
        assertThat(events.findByTaskIdOrderByCreatedAtAsc(result.taskId()))
                .extracting(TaskEvent::getEventType).containsExactly(TaskEventType.CREATED, TaskEventType.ACK_PENDING);
        verify(routing).sendTask(result.taskId());
    }

    @Test
    void duplicateReturnsExistingTaskWithoutAnotherHistory() {
        var created = service.create(teamId, agentId, request);
        var replay = service.create(teamId, agentId, request);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.taskId()).isEqualTo(created.taskId());
        assertThat(tasks.findByTeamIdOrderByCreatedAtDesc(teamId)).hasSize(1);
        assertThat(events.findByTaskIdOrderByCreatedAtAsc(created.taskId())).hasSize(2);
        verify(routing, times(1)).sendTask(created.taskId());
    }

    @Test
    void replayAfterDisconnectReturnsSavedTaskWithoutCheckingOrSendingAgain() {
        var created = service.create(teamId, agentId, request);
        clearInvocations(routing);

        var replay = service.create(teamId, agentId, request);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.taskId()).isEqualTo(created.taskId());
        assertThat(replay.delivery()).isEqualTo(created.delivery());
        assertThat(tasks.findByTeamIdOrderByCreatedAtDesc(teamId)).hasSize(1);
        verifyNoInteractions(routing);
    }

    @ParameterizedTest
    @EnumSource(value = TaskRoutingResult.Status.class, names = {
            "RECEIVER_OFFLINE", "CONNECTION_UNAVAILABLE", "CONNECTION_REPLACED"})
    void routingFailureIsRecordedAndReturned(TaskRoutingResult.Status status) {
        when(routing.sendTask(anyLong())).thenAnswer(invocation ->
                new TaskRoutingResult(invocation.getArgument(0), status, null, null));
        var result = service.create(teamId, agentId, request);
        assertThat(result.delivery()).isEqualTo(new TaskDeliveryResult(TaskEventType.DELIVERY_NOT_SENT, status.name()));
        assertThat(result.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(tasks.findById(result.taskId()).orElseThrow().getDeliveredAt()).isNull();
        var history = events.findByTaskIdOrderByCreatedAtAsc(result.taskId());
        assertThat(history).extracting(TaskEvent::getEventType)
                .containsExactly(TaskEventType.CREATED, TaskEventType.DELIVERY_NOT_SENT);
        assertThat(history.getLast().getContent()).isEqualTo(status.name());
        assertThat(service.create(teamId, agentId, request).delivery()).isEqualTo(result.delivery());
        verify(routing, times(1)).sendTask(result.taskId());
    }

    @ParameterizedTest
    @EnumSource(value = TaskRoutingResult.Status.class, names = {"TRANSPORT_ERROR", "TASK_NOT_PENDING"})
    void nonSentRoutingStoresFailure(TaskRoutingResult.Status status) {
        when(routing.sendTask(anyLong())).thenAnswer(invocation ->
                new TaskRoutingResult(invocation.getArgument(0), status, null, null));
        var expected = status == TaskRoutingResult.Status.TRANSPORT_ERROR
                ? TaskEventType.DELIVERY_UNKNOWN : TaskEventType.DELIVERY_NOT_SENT;
        var result = service.create(teamId, agentId, request);
        assertThat(result.delivery()).isEqualTo(new TaskDeliveryResult(expected, status.name()));
        assertThat(events.findByTaskIdOrderByCreatedAtAsc(result.taskId()))
                .extracting(TaskEvent::getEventType).containsExactly(TaskEventType.CREATED, expected);
    }

    @Test
    void replayReturnsStoredSentResultWithoutResending() {
        var created = service.create(teamId, agentId, request);
        var replay = service.create(teamId, agentId, request);
        assertThat(replay.delivery()).isEqualTo(created.delivery());
        assertThat(replay.delivery().status()).isEqualTo(TaskEventType.ACK_PENDING);
        verify(routing, times(1)).sendTask(created.taskId());
    }

    @Test
    void routingHistoryFailureKeepsCreatedTask() {
        doThrow(new IllegalStateException("routing history failed")).when(events)
                .save(argThat(event -> event.getEventType() == TaskEventType.ACK_PENDING));
        var response = service.create(teamId, agentId, request);
        assertThat(response.delivery()).isEqualTo(new TaskDeliveryResult(
                TaskEventType.DELIVERY_UNKNOWN, "HISTORY_RECORD_FAILED"));
        var task = tasks.findByRequesterAgentIdAndRequestKey(agentId, request.requestKey()).orElseThrow();
        assertThat(task.getDeliveredAt()).isNull();
        assertThat(task.getVersion()).isEqualTo(0L);
        assertThat(events.findByTaskIdOrderByCreatedAtAsc(task.getId()))
                .extracting(TaskEvent::getEventType).containsExactly(TaskEventType.CREATED);
    }
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @ParameterizedTest
    @EnumSource(value = TaskRoutingResult.Status.class, names = {"SENT", "TRANSPORT_ERROR"})
    void fastAckIsNotOverwrittenByRoutingResult(TaskRoutingResult.Status status) {
        when(routing.sendTask(anyLong())).thenAnswer(invocation -> {
            Long taskId = invocation.getArgument(0);
            new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                    .executeWithoutResult(tx -> {
                        var task = tasks.findByIdForUpdate(taskId).orElseThrow();
                        task.markDelivered(java.time.LocalDateTime.now());
                        events.save(TaskEvent.of(taskId, TaskEventType.ACK_RECEIVED, null));
                    });
            return new TaskRoutingResult(taskId, status, "event", "connection");
        });
        var response = service.create(teamId, agentId, request);
        assertThat(response.delivery().status()).isEqualTo(TaskEventType.ACK_RECEIVED);
        assertThat(response.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(events.findByTaskIdOrderByCreatedAtAsc(response.taskId()))
                .extracting(TaskEvent::getEventType)
                .containsExactly(TaskEventType.CREATED, TaskEventType.ACK_RECEIVED);
        assertThat(service.create(teamId, agentId, request).delivery()).isEqualTo(response.delivery());
        verify(routing, times(1)).sendTask(response.taskId());
    }

    @Test
    void timeoutBeforeSocketReturnIsNotReplacedByAckPending() {
        when(routing.isAwaitingAck(any())).thenReturn(false);
        when(routing.sendTask(anyLong())).thenAnswer(invocation -> {
            Long taskId = invocation.getArgument(0);
            new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                    .executeWithoutResult(tx -> {
                        tasks.findByIdForUpdate(taskId).orElseThrow();
                        events.save(TaskEvent.of(taskId, TaskEventType.DELIVERY_UNKNOWN, "ACK_TIMEOUT"));
                    });
            return new TaskRoutingResult(taskId, TaskRoutingResult.Status.SENT, "event", "connection");
        });
        var response = service.create(teamId, agentId, request);
        assertThat(response.delivery()).isEqualTo(
                new TaskDeliveryResult(TaskEventType.DELIVERY_UNKNOWN, "ACK_TIMEOUT"));
        assertThat(events.findByTaskIdOrderByCreatedAtAsc(response.taskId())).hasSize(2);
    }

    @Test
    void deliveryCommitFailureStillReturnsCreatedTask() {
        doAnswer(invocation -> {
            Object saved = invocation.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void beforeCommit(boolean readOnly) {
                            throw new IllegalStateException("commit failed");
                        }
                    });
            return saved;
        }).when(events).save(argThat(event -> event.getEventType() == TaskEventType.ACK_PENDING));
        var response = service.create(teamId, agentId, request);
        assertThat(response.delivery()).isEqualTo(
                new TaskDeliveryResult(TaskEventType.DELIVERY_UNKNOWN, "HISTORY_RECORD_FAILED"));
        assertThat(tasks.findById(response.taskId())).isPresent();
        assertThat(events.findByTaskIdOrderByCreatedAtAsc(response.taskId()))
                .extracting(TaskEvent::getEventType).containsExactly(TaskEventType.CREATED);
    }

    @Test
    void createdHistoryFailureRollsBackTask() {
        doThrow(new IllegalStateException("history storage failed")).when(events)
                .save(argThat(event -> event.getEventType() == TaskEventType.CREATED));
        assertThatThrownBy(() -> service.create(teamId, agentId, request))
                .isInstanceOf(IllegalStateException.class);
        assertThat(tasks.findByRequesterAgentIdAndRequestKey(agentId, request.requestKey())).isEmpty();
        verify(routing, never()).sendTask(anyLong());
    }

    @Test
    void requesterIdentityComesFromAgentAndItsOwner() {
        Long requesterId = agents.save(Agent.create(teamId, userId, "Requester", null,
                ToolType.CODEX, "project")).getId();
        var result = service.create(teamId, requesterId, request);
        var saved = tasks.findById(result.taskId()).orElseThrow();
        assertThat(saved.getRequesterAgentId()).isEqualTo(requesterId);
        assertThat(saved.getRequesterUserId()).isEqualTo(userId);
        assertThat(saved.getReceiverAgentId()).isEqualTo(agentId);
    }

    @Test
    void missingRequesterDoesNotCreateTask() {
        assertThatThrownBy(() -> service.create(teamId, Long.MAX_VALUE, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThat(tasks.findByTeamIdOrderByCreatedAtDesc(teamId)).isEmpty();
        verifyNoInteractions(routing);
    }

    @Test
    void requesterFromAnotherTeamDoesNotCreateTask() {
        Long otherTeamId = teams.save(Team.create("Other", UUID.randomUUID().toString())).getId();
        Long otherAgentId = agents.save(Agent.create(otherTeamId, userId, "Other", null,
                ToolType.CODEX, "project")).getId();
        assertThatThrownBy(() -> service.create(teamId, otherAgentId, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThat(tasks.findByTeamIdOrderByCreatedAtDesc(teamId)).isEmpty();
        verifyNoInteractions(routing);
    }

    @Test
    void sameOwnerAndKeyWithDifferentRequesterCreatesSeparateTask() {
        var first = service.create(teamId, agentId, request);
        Long otherAgentId = agents.save(Agent.create(teamId, userId, "Other", null,
                ToolType.CODEX, "project")).getId();
        var second = service.create(teamId, otherAgentId, request);
        assertThat(second.replayed()).isFalse();
        assertThat(second.taskId()).isNotEqualTo(first.taskId());
        assertThat(tasks.findByTeamIdOrderByCreatedAtDesc(teamId)).hasSize(2);
        verify(routing, times(2)).sendTask(anyLong());
    }

    @Test
    void missingReceiverDoesNotCreateTask() {
        var invalid = new CreateTaskRequest(request.requestKey(), Long.MAX_VALUE, "Prompt");
        assertThatThrownBy(() -> service.create(teamId, agentId, invalid))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThat(tasks.findByRequesterAgentIdAndRequestKey(agentId, request.requestKey())).isEmpty();
    }

    @Test
    void sameKeyWithDifferentPayloadConflicts() {
        service.create(teamId, agentId, request);
        var changed = new CreateTaskRequest(request.requestKey(), agentId, "Different prompt");
        assertThatThrownBy(() -> service.create(teamId, agentId, changed))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.REQUEST_KEY_CONFLICT));
    }

    @Test
    void summaryIsStoredTrimmed() {
        var withSummary = new CreateTaskRequest(request.requestKey(), agentId, "  작업 요약\n둘째 줄  ", request.prompt());
        var created = service.create(teamId, agentId, withSummary);
        assertThat(created.replayed()).isFalse();
        assertThat(tasks.findById(created.taskId()).orElseThrow().getTaskSummary()).isEqualTo("작업 요약\n둘째 줄");
    }

    @Test
    void missingSummaryIsStoredAsNull() {
        var created = service.create(teamId, agentId, request);
        assertThat(tasks.findById(created.taskId()).orElseThrow().getTaskSummary()).isNull();
    }

    @Test
    void blankSummaryIsRejectedBeforeSavingTask() {
        var blank = new CreateTaskRequest(request.requestKey(), agentId, "   ", request.prompt());
        assertThatThrownBy(() -> service.create(teamId, agentId, blank))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(tasks.findByRequesterAgentIdAndRequestKey(agentId, request.requestKey())).isEmpty();
        verify(routing, never()).sendTask(anyLong());
    }

    @Test
    void summaryLengthIsCountedInCodePoints() {
        // 😀 is two UTF-16 chars but one code point: 1,000 of them pass, 1,001 fail.
        String limit = "😀".repeat(1000);
        var atLimit = new CreateTaskRequest(request.requestKey(), agentId, limit, request.prompt());
        var created = service.create(teamId, agentId, atLimit);
        assertThat(tasks.findById(created.taskId()).orElseThrow().getTaskSummary()).isEqualTo(limit);

        var over = new CreateTaskRequest(UUID.randomUUID().toString(), agentId, limit + "😀", request.prompt());
        assertThatThrownBy(() -> service.create(teamId, agentId, over))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(tasks.findByRequesterAgentIdAndRequestKey(agentId, over.requestKey())).isEmpty();
    }

    @Test
    void sameKeyWithDifferentSummaryConflicts() {
        service.create(teamId, agentId, new CreateTaskRequest(request.requestKey(), agentId, "요약", request.prompt()));
        var changed = new CreateTaskRequest(request.requestKey(), agentId, "다른 요약", request.prompt());
        assertThatThrownBy(() -> service.create(teamId, agentId, changed))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.REQUEST_KEY_CONFLICT));
        // A summary added to a key that was created without one is also a different payload.
        var withoutSummary = new CreateTaskRequest(UUID.randomUUID().toString(), agentId, request.prompt());
        service.create(teamId, agentId, withoutSummary);
        var added = new CreateTaskRequest(withoutSummary.requestKey(), agentId, "요약", request.prompt());
        assertThatThrownBy(() -> service.create(teamId, agentId, added))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.REQUEST_KEY_CONFLICT));
    }

    @Test
    void replayWithWhitespaceVariantSummaryIsAccepted() {
        var created = service.create(teamId, agentId,
                new CreateTaskRequest(request.requestKey(), agentId, "요약", request.prompt()));
        var replay = service.create(teamId, agentId,
                new CreateTaskRequest(request.requestKey(), agentId, "  요약\t", request.prompt()));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.taskId()).isEqualTo(created.taskId());
        assertThat(tasks.findByTeamIdOrderByCreatedAtDesc(teamId)).hasSize(1);
    }

    @Test
    void summarySurvivesStatusChangesAndCompletion() {
        var created = service.create(teamId, agentId,
                new CreateTaskRequest(request.requestKey(), agentId, "요약", request.prompt()));
        var task = tasks.findById(created.taskId()).orElseThrow();
        task.accept(agentId);
        task.complete(agentId, "done");
        tasks.saveAndFlush(task);
        var reloaded = tasks.findById(created.taskId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(reloaded.getTaskSummary()).isEqualTo("요약");
    }

    @Test
    void concurrentInsertLoserRecoversAsReplayAndOnlyWinnerDelivers() throws Exception {
        var ready = new CountDownLatch(2);
        // Force both requests past the same empty pre-check once; INSERT, rollback and the
        // recovery lookup use PostgreSQL.
        org.mockito.stubbing.Answer<Object> gate = invocation -> {
            ready.countDown();
            if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Missing concurrent request");
            return java.util.Optional.empty();
        };
        // Both pre-checks see empty; every later call (recovery lookup) reads the committed row.
        // doCallRealMethod is unavailable on the repository proxy, so go through an unstubbed query.
        org.mockito.stubbing.Answer<Object> committed = invocation -> tasks.findByTeamIdOrderByCreatedAtDesc(teamId)
                .stream().filter(t -> t.getRequesterAgentId().equals(agentId)
                        && t.getRequestKey().equals(request.requestKey())).findFirst();
        doAnswer(gate).doAnswer(gate).doAnswer(committed)
                .when(tasks).findByRequesterAgentIdAndRequestKey(agentId, request.requestKey());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.create(teamId, agentId, request));
            var second = executor.submit(() -> service.create(teamId, agentId, request));
            var results = java.util.List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(results).extracting(r -> r.replayed()).containsExactlyInAnyOrder(false, true);
            assertThat(results).extracting(r -> r.taskId()).containsOnly(results.getFirst().taskId());
            var saved = tasks.findByTeamIdOrderByCreatedAtDesc(teamId);
            assertThat(saved).hasSize(1);
            assertThat(events.findByTaskIdOrderByCreatedAtAsc(saved.getFirst().getId()))
                    .extracting(TaskEvent::getEventType).containsExactly(TaskEventType.CREATED, TaskEventType.ACK_PENDING);
            verify(routing, times(1)).sendTask(saved.getFirst().getId());
        }
    }

    @Test
    void concurrentInsertLoserWithDifferentPayloadConflicts() throws Exception {
        var ready = new CountDownLatch(2);
        org.mockito.stubbing.Answer<Object> gate = invocation -> {
            ready.countDown();
            if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Missing concurrent request");
            return java.util.Optional.empty();
        };
        // Both pre-checks see empty; every later call (recovery lookup) reads the committed row.
        // doCallRealMethod is unavailable on the repository proxy, so go through an unstubbed query.
        org.mockito.stubbing.Answer<Object> committed = invocation -> tasks.findByTeamIdOrderByCreatedAtDesc(teamId)
                .stream().filter(t -> t.getRequesterAgentId().equals(agentId)
                        && t.getRequestKey().equals(request.requestKey())).findFirst();
        doAnswer(gate).doAnswer(gate).doAnswer(committed)
                .when(tasks).findByRequesterAgentIdAndRequestKey(agentId, request.requestKey());
        var changed = new CreateTaskRequest(request.requestKey(), agentId, "Different prompt");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.create(teamId, agentId, request));
            var second = executor.submit(() -> service.create(teamId, agentId, changed));
            int successes = 0;
            int conflicts = 0;
            for (var future : java.util.List.of(first, second)) {
                try {
                    assertThat(future.get(20, TimeUnit.SECONDS).replayed()).isFalse();
                    successes++;
                } catch (ExecutionException exception) {
                    assertThat(exception.getCause()).isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.REQUEST_KEY_CONFLICT));
                    conflicts++;
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
            assertThat(tasks.findByTeamIdOrderByCreatedAtDesc(teamId)).hasSize(1);
        }
    }

    @Test
    void nonKeyConstraintViolationIsNotRecovered() {
        var violation = new org.hibernate.exception.ConstraintViolationException("other",
                new java.sql.SQLException("other"), "fk_tasks_other");
        var failure = new DataIntegrityViolationException("other", violation);
        doThrow(failure).when(tasks).save(any());
        assertThatThrownBy(() -> service.create(teamId, agentId, request)).isSameAs(failure);
        // Only the pre-check ran; no recovery lookup for an unrelated constraint.
        verify(tasks, times(1)).findByRequesterAgentIdAndRequestKey(agentId, request.requestKey());
        assertThat(tasks.findByTeamIdOrderByCreatedAtDesc(teamId)).isEmpty();
    }

    @Test
    void keyConflictWithoutRecoverableTaskRethrowsOriginal() {
        var violation = new org.hibernate.exception.ConstraintViolationException("duplicate",
                new java.sql.SQLException("duplicate"), "uk_tasks_request_key");
        var failure = new DataIntegrityViolationException("duplicate", violation);
        doThrow(failure).when(tasks).save(any());
        assertThatThrownBy(() -> service.create(teamId, agentId, request)).isSameAs(failure);
        // Pre-check plus one recovery lookup, nothing written, no delivery.
        verify(tasks, times(2)).findByRequesterAgentIdAndRequestKey(agentId, request.requestKey());
        assertThat(tasks.findByTeamIdOrderByCreatedAtDesc(teamId)).isEmpty();
        verify(routing, never()).sendTask(anyLong());
    }
}
