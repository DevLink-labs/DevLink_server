package com.devlink.server.service;

import static com.devlink.server.AckTestDependencies.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.dto.TaskAckResult;
import com.devlink.server.dto.TaskAckResult.Status;
import com.devlink.server.realtime.BrokerMessageSender;
import com.devlink.server.realtime.ConnectionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskAckServiceTest {
    private final ConnectionRegistry connections = new ConnectionRegistry();
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final AtomicLong clock = new AtomicLong();
    private final com.devlink.server.repository.TaskRepository tasks = tasks();
    private final com.devlink.server.repository.TaskEventRepository history = history();
    private final org.springframework.transaction.PlatformTransactionManager transactions = transactionManager();
    private final TaskAckService acknowledgements = new TaskAckService(
            connections, events, tasks, history, transactions, 5000, clock::get);
    private final AgentPrincipal receiver = new AgentPrincipal(11, 21, 31);

    @Test
    void validAckCompletesOnceAndDuplicateOrUnknownAckDoesNothing() {
        WebSocketSession session = session("first");
        var result = acknowledgements.register(7, "event-1", receiver, session).toCompletableFuture();

        assertThat(acknowledgements.acknowledge(receiver, session, 7, "event-1")).isTrue();
        assertThat(result.join()).isEqualTo(new TaskAckResult(7, "event-1", "first", Status.ACKNOWLEDGED));
        assertThat(acknowledgements.acknowledge(receiver, session, 7, "event-1")).isFalse();
        assertThat(acknowledgements.acknowledge(receiver, session, 7, "unknown")).isFalse();
        clock.set(Duration.ofSeconds(10).toNanos());
        acknowledgements.checkTimeouts();

        verify(events, times(1)).publishEvent(any(TaskAckResult.class));
    }

    @ParameterizedTest
    @CsvSource({"99,21,31", "11,99,31", "11,21,99"})
    void wrongAgentOwnerOrTeamCannotConsumeValidWait(long agentId, long ownerId, long teamId) {
        WebSocketSession session = session("first");
        var result = acknowledgements.register(7, "event-1", receiver, session).toCompletableFuture();

        assertThat(acknowledgements.acknowledge(new AgentPrincipal(agentId, ownerId, teamId),
                session, 7, "event-1")).isFalse();
        assertThat(result).isNotDone();
        assertThat(acknowledgements.acknowledge(receiver, session, 7, "event-1")).isTrue();
    }

    @Test
    void wrongTaskAndOtherEventsDoNotFinishWait() {
        WebSocketSession session = session("first");
        var first = acknowledgements.register(7, "event-1", receiver, session).toCompletableFuture();
        var second = acknowledgements.register(8, "event-2", receiver, session).toCompletableFuture();

        assertThat(acknowledgements.acknowledge(receiver, session, 8, "event-1")).isFalse();
        assertThat(first).isNotDone();
        assertThat(second).isNotDone();
        assertThat(acknowledgements.acknowledge(receiver, session, 7, "event-1")).isTrue();
        assertThat(second).isNotDone();
    }

    @Test
    void ackAtDeadlineIsRejectedEvenBeforeScheduledScan() {
        WebSocketSession session = session("first");
        var result = acknowledgements.register(7, "event-1", receiver, session).toCompletableFuture();
        clock.set(Duration.ofSeconds(5).toNanos() - 1);
        acknowledgements.checkTimeouts();
        assertThat(result).isNotDone();

        clock.incrementAndGet();
        assertThat(acknowledgements.acknowledge(receiver, session, 7, "event-1")).isFalse();
        assertThat(result.join().status()).isEqualTo(Status.TIMED_OUT);
        acknowledgements.checkTimeouts();
        verify(events, times(1)).publishEvent(any(TaskAckResult.class));
    }

    @Test
    void scheduledTimeoutIgnoresLateAckAndDoesNotOverwriteResult() {
        WebSocketSession session = session("first");
        var result = acknowledgements.register(7, "event-1", receiver, session).toCompletableFuture();
        clock.set(Duration.ofSeconds(5).toNanos());

        acknowledgements.checkTimeouts();

        assertThat(result.join().status()).isEqualTo(Status.TIMED_OUT);
        assertThat(acknowledgements.acknowledge(receiver, session, 7, "event-1")).isFalse();
        verify(events, times(1)).publishEvent(any(TaskAckResult.class));
    }

    @Test
    void previousAndReplacementSessionsCannotAcknowledgeEachOthersEvents() {
        WebSocketSession previous = session("previous");
        var oldResult = acknowledgements.register(7, "old", receiver, previous).toCompletableFuture();
        WebSocketSession current = session("current");
        var newResult = acknowledgements.register(8, "new", receiver, current).toCompletableFuture();

        assertThat(acknowledgements.acknowledge(receiver, previous, 7, "old")).isFalse();
        assertThat(acknowledgements.acknowledge(receiver, current, 7, "old")).isFalse();
        acknowledgements.connectionClosed(previous);
        assertThat(oldResult.join().status()).isEqualTo(Status.CONNECTION_CLOSED);
        assertThat(newResult).isNotDone();
        assertThat(acknowledgements.acknowledge(receiver, current, 8, "new")).isTrue();
    }

    @Test
    void missingCloseCallbackIsDetectedByConnectionScan() {
        WebSocketSession previous = session("previous");
        var result = acknowledgements.register(7, "event", receiver, previous).toCompletableFuture();
        session("current");

        acknowledgements.checkTimeouts();

        assertThat(result.join().status()).isEqualTo(Status.CONNECTION_CLOSED);
    }

    @Test
    void sendFailureReleasesWaitButCannotOverwriteAckThatAlreadyWon() {
        WebSocketSession session = session("first");
        var failed = acknowledgements.register(7, "failed", receiver, session).toCompletableFuture();
        acknowledgements.sendFailed("failed", session);
        assertThat(failed.join().status()).isEqualTo(Status.SEND_FAILED);
        assertThat(acknowledgements.acknowledge(receiver, session, 7, "failed")).isFalse();

        var successful = acknowledgements.register(8, "ok", receiver, session).toCompletableFuture();
        acknowledgements.acknowledge(receiver, session, 8, "ok");
        acknowledgements.sendFailed("ok", session);
        assertThat(successful.join().status()).isEqualTo(Status.ACKNOWLEDGED);
        verify(events, times(2)).publishEvent(any(TaskAckResult.class));
    }

    @Test
    void duplicateRegistrationDoesNotReplaceOriginalWait() {
        WebSocketSession session = session("first");
        var original = acknowledgements.register(7, "event", receiver, session).toCompletableFuture();

        assertThatIllegalStateException().isThrownBy(() ->
                acknowledgements.register(8, "event", receiver, session));

        acknowledgements.acknowledge(receiver, session, 7, "event");
        assertThat(original.join().taskId()).isEqualTo(7);
    }

    @Test
    void anotherAttemptCannotRegisterUntilTheAckCommits() throws Exception {
        WebSocketSession session = session("first");
        var committing = new CountDownLatch(1);
        var finishCommit = new CountDownLatch(1);
        doAnswer(invocation -> {
            committing.countDown();
            assertThat(finishCommit.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(transactions).commit(any());
        acknowledgements.register(7, "first", receiver, session);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var ack = executor.submit(() -> acknowledgements.acknowledge(receiver, session, 7, "first"));
            try {
                assertThat(committing.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatIllegalStateException().isThrownBy(() ->
                        acknowledgements.register(7, "second", receiver, session));
            } finally {
                finishCommit.countDown();
            }
            assertThat(ack.get(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(acknowledgements.register(7, "second", receiver, session).toCompletableFuture()).isNotDone();
    }

    @Test
    void listenerFailureDoesNotBlockOtherWaitsFromFinishing() {
        WebSocketSession session = session("first");
        doThrow(new IllegalStateException("Result consumer failed"))
                .when(events).publishEvent(any(TaskAckResult.class));
        var first = acknowledgements.register(7, "first", receiver, session).toCompletableFuture();
        var second = acknowledgements.register(8, "second", receiver, session).toCompletableFuture();
        clock.set(Duration.ofSeconds(5).toNanos());

        acknowledgements.checkTimeouts();

        assertThat(first.join().status()).isEqualTo(Status.TIMED_OUT);
        assertThat(second.join().status()).isEqualTo(Status.TIMED_OUT);
    }

    @Test
    void ackResultConsumerCanReconnectWithoutRegistryLockOverwritingNewConnection() {
        WebSocketSession previous = session("previous");
        WebSocketSession replacement = mock(WebSocketSession.class);
        when(replacement.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            connections.register("11", replacement);
            return null;
        }).when(events).publishEvent(any(TaskAckResult.class));
        acknowledgements.register(7, "event", receiver, previous);

        assertThat(acknowledgements.acknowledge(receiver, previous, 7, "event")).isTrue();

        assertThat(connections.findByAgentId("11")).contains(replacement);
    }

    @Test
    void competingTimeoutCloseAndSendFailurePublishOnlyOneTerminalResult() throws Exception {
        WebSocketSession session = session("first");
        var result = acknowledgements.register(7, "event", receiver, session).toCompletableFuture();
        clock.set(Duration.ofSeconds(5).toNanos());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var actions = List.<Runnable>of(acknowledgements::checkTimeouts,
                    () -> acknowledgements.connectionClosed(session),
                    () -> acknowledgements.sendFailed("event", session),
                    () -> acknowledgements.acknowledge(receiver, session, 7, "event"));
            var jobs = actions.stream().map(action -> executor.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                action.run();
                return null;
            })).toList();
            start.countDown();
            for (var job : jobs) {
                job.get(5, TimeUnit.SECONDS);
            }
        }
        assertThat(result).isCompleted();
        verify(events, times(1)).publishEvent(any(TaskAckResult.class));
    }

    @Test
    void socketQueueWaitingDoesNotUseUpAckTimeout() throws Exception {
        WebSocketSession session = session("first");
        var sender = new BrokerMessageSender(connections, new ObjectMapper());
        var entered = new CountDownLatch(1);
        var result = new AtomicReference<CompletionStage<TaskAckResult>>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            java.util.concurrent.Future<Boolean> sending;
            synchronized (session) {
                sending = executor.submit(() -> {
                    entered.countDown();
                    return sender.sendIfCurrent("11", session, Map.of("type", "TASK_CREATED"),
                            () -> result.set(acknowledgements.register(7, "event", receiver, session)));
                });
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                // 다른 전송을 기다리는 동안 1분이 흘러도 ACK 대기는 아직 시작하지 않는다.
                clock.set(Duration.ofMinutes(1).toNanos());
            }
            assertThat(sending.get(5, TimeUnit.SECONDS)).isTrue();
        }
        clock.addAndGet(Duration.ofSeconds(5).toNanos() - 1);
        acknowledgements.checkTimeouts();
        assertThat(result.get().toCompletableFuture()).isNotDone();
        clock.incrementAndGet();
        acknowledgements.checkTimeouts();
        assertThat(result.get().toCompletableFuture().join().status()).isEqualTo(Status.TIMED_OUT);
    }

    @Test
    void validAckLocksTaskAndRecordsDeliveryBeforeCompleting() {
        WebSocketSession session = session("first");
        var task = mock(com.devlink.server.domain.task.Task.class);
        when(tasks.findByIdForUpdate(7L)).thenReturn(java.util.Optional.of(task));
        var result = acknowledgements.register(7, "event", receiver, session).toCompletableFuture();
        acknowledgements.acknowledge(receiver, session, 7, "event");
        var order = org.mockito.Mockito.inOrder(tasks, task, history, transactions, events);
        order.verify(tasks).findByIdForUpdate(7L);
        order.verify(history).existsByTaskIdAndEventType(7L,
                com.devlink.server.domain.taskevent.TaskEventType.ACK_RECEIVED);
        order.verify(task).markDelivered(any());
        order.verify(history).save(org.mockito.ArgumentMatchers.argThat(event ->
                event.getEventType() == com.devlink.server.domain.taskevent.TaskEventType.ACK_RECEIVED
                        && event.getContent() == null));
        order.verify(transactions).commit(any());
        order.verify(events).publishEvent(any(TaskAckResult.class));
        assertThat(result.join().status()).isEqualTo(Status.ACKNOWLEDGED);
    }

    @Test
    void existingAcknowledgementPreventsTimeoutHistory() {
        WebSocketSession session = session("first");
        when(history.existsByTaskIdAndEventType(7L,
                com.devlink.server.domain.taskevent.TaskEventType.ACK_RECEIVED)).thenReturn(true);
        acknowledgements.register(7, "event", receiver, session);
        clock.set(Duration.ofSeconds(5).toNanos());
        acknowledgements.checkTimeouts();
        verify(history, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void ackCommitFailureDoesNotLeaveFutureIncomplete() {
        WebSocketSession session = session("first");
        doThrow(new IllegalStateException("commit failed")).when(transactions).commit(any());
        var result = acknowledgements.register(7, "event", receiver, session).toCompletableFuture();
        assertThat(acknowledgements.acknowledge(receiver, session, 7, "event")).isTrue();
        assertThat(result.join().status()).isEqualTo(Status.ACKNOWLEDGED);
        verify(events).publishEvent(any(TaskAckResult.class));
    }

    @Test
    void sendFailureDoesNotWriteDuplicateHistory() {
        WebSocketSession session = session("first");
        acknowledgements.register(7, "event", receiver, session);
        acknowledgements.sendFailed("event", session);
        org.mockito.Mockito.verifyNoInteractions(tasks, history, transactions);
    }

    private WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn(id);
        connections.register("11", session);
        return session;
    }
}
