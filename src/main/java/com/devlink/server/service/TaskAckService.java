package com.devlink.server.service;

import java.time.Duration;
import java.time.LocalDateTime;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TaskEventRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.dto.TaskAckResult;
import com.devlink.server.dto.TaskAckResult.Status;
import com.devlink.server.realtime.ConnectionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.socket.WebSocketSession;

/** 전송 시도별 ACK 대기를 메모리에 관리한다. 검증된 ACK와 종료 결과를 별도 트랜잭션으로 기록한다. */
@Service
public class TaskAckService {
    private static final Logger log = LoggerFactory.getLogger(TaskAckService.class);
    private final ConcurrentMap<String, PendingAck> pending = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, PendingAck> pendingByTask = new ConcurrentHashMap<>();
    private final ConnectionRegistry connections;
    private final ApplicationEventPublisher events;
    private final TaskRepository tasks;
    private final TaskEventRepository history;
    private final TransactionTemplate transaction;
    private final long timeoutNanos;
    private final LongSupplier nanoTime;

    @Autowired
    public TaskAckService(ConnectionRegistry connections, ApplicationEventPublisher events,
            TaskRepository tasks, TaskEventRepository history, PlatformTransactionManager transactionManager,
            @Value("${devlink.broker.ack-timeout-ms:5000}") long timeoutMillis) {
        this(connections, events, tasks, history, transactionManager, timeoutMillis, System::nanoTime);
    }

    TaskAckService(ConnectionRegistry connections, ApplicationEventPublisher events,
            TaskRepository tasks, TaskEventRepository history, PlatformTransactionManager transactionManager,
            long timeoutMillis, LongSupplier nanoTime) {
        Assert.isTrue(timeoutMillis > 0, "ack timeout must be positive");
        this.connections = connections;
        this.events = events;
        this.tasks = tasks;
        this.history = history;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.timeoutNanos = Duration.ofMillis(timeoutMillis).toNanos();
        this.nanoTime = nanoTime;
    }

    /** 소켓 쓰기 직전에 등록해 즉시 돌아오는 ACK를 놓치지 않고, 실제 전송 시작부터 시간을 잰다. */
    public CompletionStage<TaskAckResult> register(long taskId, String eventId,
            AgentPrincipal receiver, WebSocketSession session) {
        return register(taskId, eventId, receiver, session, () -> { });
    }

    CompletionStage<TaskAckResult> register(long taskId, String eventId,
            AgentPrincipal receiver, WebSocketSession session, Runnable recheckTask) {
        Assert.isTrue(taskId > 0, "taskId must be positive");
        Assert.hasText(eventId, "eventId must not be blank");
        Assert.notNull(receiver, "receiver must not be null");
        Assert.notNull(session, "session must not be null");
        PendingAck waiting = new PendingAck(taskId, eventId, receiver, session);
        if (!connections.executeIfCurrent(receiver.getName(), session, () -> {
            if (pendingByTask.putIfAbsent(taskId, waiting) != null) {
                throw new AckInProgressException();
            }
            return true;
        })) {
            throw new ConnectionReplacedException();
        }
        try {
            // 작업별 자리를 먼저 확보하되, DB 재확인 시간은 ACK 제한 시간에서 제외한다.
            recheckTask.run();
            if (!connections.executeIfCurrent(receiver.getName(), session, () -> {
                waiting.startedAt = nanoTime.getAsLong();
                if (pending.putIfAbsent(eventId, waiting) != null) {
                    throw new IllegalStateException("ACK already registered for event " + eventId);
                }
                return true;
            })) {
                throw new ConnectionReplacedException();
            }
        } catch (RuntimeException exception) {
            release(waiting, Status.SEND_FAILED);
            throw exception;
        }
        return waiting.result.minimalCompletionStage();
    }

    void finishPreviousAttempt(long taskId, WebSocketSession session) {
        PendingAck waiting = pendingByTask.get(taskId);
        if (waiting != null && waiting.session != session
                && !connections.isCurrentConnection(waiting.receiver.getName(), waiting.session)) {
            // 이전 연결의 종료 콜백이 늦어도 새 연결에서 전송할 수 있게 정리한다.
            finish(waiting, Status.CONNECTION_CLOSED);
            waiting.result.join();
        }
    }

    boolean isAwaitingAck(long taskId, String eventId) {
        PendingAck waiting = pending.get(eventId);
        return waiting != null && waiting.taskId == taskId;
    }

    /** 다른 Agent·작업·이전 연결이 보낸 ACK는 올바른 대기에 영향을 주지 않고 무시한다. */
    public boolean acknowledge(AgentPrincipal receiver, WebSocketSession session, long taskId, String eventId) {
        PendingAck waiting = pending.get(eventId);
        if (waiting == null || waiting.taskId != taskId || !waiting.receiver.equals(receiver)
                || waiting.session != session) {
            return false;
        }
        Status[] status = {Status.ACKNOWLEDGED};
        boolean claimed = connections.executeIfCurrent(receiver.getName(), session, () -> {
            if (isExpired(waiting)) {
                status[0] = Status.TIMED_OUT;
            }
            return pending.remove(eventId, waiting);
        });
        if (!claimed) {
            return false;
        }
        complete(waiting, status[0]);
        return status[0] == Status.ACKNOWLEDGED;
    }

    /** 전송 실패 시 즉시 대기를 해제한다. 먼저 확정된 ACK 성공 결과는 덮어쓰지 않는다. */
    public void sendFailed(String eventId, WebSocketSession session) {
        PendingAck waiting = pending.get(eventId);
        if (waiting != null && waiting.session == session) {
            finish(waiting, Status.SEND_FAILED);
        }
    }

    /** 종료를 감지한 연결의 대기만 해제하며, 재접속한 새 연결의 대기는 유지한다. */
    public void connectionClosed(WebSocketSession session) {
        pending.forEach((eventId, waiting) -> {
            if (waiting.session == session) {
                finish(waiting, Status.CONNECTION_CLOSED);
            }
        });
    }

    @Scheduled(fixedDelayString = "${devlink.broker.ack-check-interval-ms:100}")
    public void checkTimeouts() {
        pending.forEach((eventId, waiting) -> {
            if (!connections.isCurrentConnection(waiting.receiver.getName(), waiting.session)) {
                finish(waiting, Status.CONNECTION_CLOSED);
            } else if (isExpired(waiting)) {
                finish(waiting, Status.TIMED_OUT);
            }
        });
    }

    private boolean isExpired(PendingAck waiting) {
        return nanoTime.getAsLong() - waiting.startedAt >= timeoutNanos;
    }

    private boolean finish(PendingAck waiting, Status status) {
        if (!pending.remove(waiting.eventId, waiting)) {
            return false;
        }
        complete(waiting, status);
        return true;
    }

    private void complete(PendingAck waiting, Status status) {
        try {
            saveAckResult(waiting, status);
        } catch (RuntimeException exception) {
            log.error("Failed to record ACK result: taskId={}, eventId={}, status={}",
                    waiting.taskId, waiting.eventId, status, exception);
        }
        var result = release(waiting, status);
        try {
            events.publishEvent(result);
        } catch (RuntimeException exception) {
            log.error("Failed to publish ACK result for event {}", waiting.eventId, exception);
        }
    }

    private TaskAckResult release(PendingAck waiting, Status status) {
        // ACK 이력 커밋이 끝날 때까지 같은 작업에 새 전송을 등록하지 못하게 한다.
        pendingByTask.remove(waiting.taskId, waiting);
        var result = new TaskAckResult(waiting.taskId, waiting.eventId, waiting.session.getId(), status);
        waiting.result.complete(result);
        return result;
    }

    private void saveAckResult(PendingAck waiting, Status status) {
        // 전송 실패는 생성 서비스가 TRANSPORT_ERROR로 기록한다.
        if (status == Status.SEND_FAILED) return;
        transaction.executeWithoutResult(tx -> {
            Task task = tasks.findByIdForUpdate(waiting.taskId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            if (history.existsByTaskIdAndEventType(waiting.taskId, TaskEventType.ACK_RECEIVED)) return;
            switch (status) {
                case ACKNOWLEDGED -> {
                    task.markDelivered(LocalDateTime.now());
                    history.save(TaskEvent.of(waiting.taskId, TaskEventType.ACK_RECEIVED, null));
                }
                case TIMED_OUT -> history.save(TaskEvent.of(
                        waiting.taskId, TaskEventType.DELIVERY_UNKNOWN, "ACK_TIMEOUT"));
                case CONNECTION_CLOSED -> history.save(TaskEvent.of(
                        waiting.taskId, TaskEventType.DELIVERY_UNKNOWN, "CONNECTION_CLOSED"));
                case SEND_FAILED -> { }
            }
        });
    }

    static final class AckInProgressException extends IllegalStateException { }

    static final class ConnectionReplacedException extends IllegalStateException { }

    private static final class PendingAck {
        private final long taskId;
        private final String eventId;
        private final AgentPrincipal receiver;
        private final WebSocketSession session;
        private long startedAt;
        private final CompletableFuture<TaskAckResult> result = new CompletableFuture<>();

        private PendingAck(long taskId, String eventId, AgentPrincipal receiver, WebSocketSession session) {
            this.taskId = taskId;
            this.eventId = eventId;
            this.receiver = receiver;
            this.session = session;
        }
    }
}
