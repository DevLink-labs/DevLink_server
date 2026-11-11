package com.devlink.server.service;

import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.UserPrincipal;
import com.devlink.server.realtime.WebEventEntityListener.AgentChanged;
import com.devlink.server.realtime.WebEventEntityListener.MembershipChanged;
import com.devlink.server.realtime.WebEventEntityListener.TaskChanged;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class WebEventStreamService {
    private final Map<UUID, Connection> connections = new ConcurrentHashMap<>();
    private final WebEventQueryService queries;
    private final Executor executor;
    private final int maxConnections;

    public WebEventStreamService(WebEventQueryService queries,
            @Qualifier("webEventExecutor") Executor executor,
            @Value("${devlink.web-events.max-connections-per-user:6}") int maxConnections) {
        if (maxConnections < 1) throw new IllegalArgumentException("max connections must be positive");
        this.queries = queries;
        this.executor = executor;
        this.maxConnections = maxConnections;
    }

    public synchronized SseEmitter subscribe(UserPrincipal user, HttpSession session) {
        if (user == null || session == null || !authenticated(session, user.userId())) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        var previous = connections.values().stream().filter(item -> item.userId == user.userId())
                .sorted(Comparator.comparingLong(item -> item.createdAt)).toList();
        for (int index = 0; index <= previous.size() - maxConnections; index++) {
            var replaced = previous.get(index);
            send(replaced, SseEmitter.event().name("subscription-replaced").data(Map.of()));
            close(replaced);
        }
        var emitter = createEmitter();
        var connection = new Connection(UUID.randomUUID(), user.userId(), session.getId(), session, emitter);
        connections.put(connection.id, connection);
        emitter.onCompletion(() -> {
            connections.remove(connection.id);
            connection.pending.clear();
        });
        emitter.onTimeout(() -> close(connection));
        emitter.onError(error -> close(connection));
        send(connection, SseEmitter.event().name("ready").id("").reconnectTime(3_000).data(Map.of()));
        return emitter;
    }

    SseEmitter createEmitter() {
        return new SseEmitter(30 * 60_000L);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void taskChanged(TaskChanged change) {
        for (var connection : connections.values()) submit(connection, () -> {
            var update = queries.task(connection.userId, change.taskId(), change.eventId());
            if (update == null) return;
            var event = SseEmitter.event().name("task-updated").data(update);
            if (update.event() != null) event.id(update.event().eventId());
            send(connection, event);
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void agentChanged(AgentChanged change) {
        for (var connection : connections.values()) submit(connection, () -> {
            var update = queries.agent(connection.userId, change.agentId(), change.teamId(), change.deleted());
            if (update != null) send(connection, SseEmitter.event().name("agent-presence").data(update));
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void membershipChanged(MembershipChanged change) {
        connections.values().stream().filter(item -> item.userId == change.userId()).forEach(this::close);
    }

    @Scheduled(fixedDelayString = "${devlink.web-events.heartbeat-interval-ms:15000}")
    public void heartbeat() {
        for (var connection : connections.values()) submit(connection,
                () -> send(connection, SseEmitter.event().comment("keep-alive")));
    }

    public void closeSession(String sessionId) {
        connections.values().stream().filter(item -> item.sessionId.equals(sessionId)).forEach(this::close);
    }

    private void submit(Connection connection, Runnable action) {
        if (!connections.containsKey(connection.id)) return;
        if (!connection.pending.offer(action)) {
            close(connection);
            return;
        }
        drain(connection);
    }

    private void drain(Connection connection) {
        if (!connection.sending.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    Runnable action;
                    while ((action = connection.pending.poll()) != null) {
                        if (!connections.containsKey(connection.id)) return;
                        if (!authenticated(connection.session, connection.userId)) {
                            close(connection);
                            return;
                        }
                        try {
                            action.run();
                        } catch (RuntimeException exception) {
                            close(connection);
                            return;
                        }
                    }
                } finally {
                    connection.sending.set(false);
                    if (connections.containsKey(connection.id) && !connection.pending.isEmpty()) drain(connection);
                }
            });
        } catch (RejectedExecutionException exception) {
            connection.sending.set(false);
            close(connection);
        }
    }

    private void send(Connection connection, SseEmitter.SseEventBuilder event) {
        synchronized (connection) {
            if (!connections.containsKey(connection.id)) return;
            if (!authenticated(connection.session, connection.userId)) {
                close(connection);
                return;
            }
            try {
                connection.emitter.send(event);
            } catch (IOException | IllegalStateException exception) {
                close(connection);
            }
        }
    }

    private boolean authenticated(HttpSession session, long userId) {
        try {
            var context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            if (!(context instanceof SecurityContext security)) return false;
            var authentication = security.getAuthentication();
            return authentication != null && authentication.isAuthenticated()
                    && authentication.getPrincipal() instanceof UserPrincipal user && user.userId() == userId;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    private void close(Connection connection) {
        if (connections.remove(connection.id) != null) {
            connection.pending.clear();
            connection.emitter.complete();
        }
    }

    @PreDestroy
    public void shutdown() {
        connections.values().forEach(this::close);
    }

    private static final class Connection {
        private final UUID id;
        private final long userId;
        private final String sessionId;
        private final HttpSession session;
        private final SseEmitter emitter;
        private final long createdAt = System.nanoTime();
        private final ArrayBlockingQueue<Runnable> pending = new ArrayBlockingQueue<>(64);
        private final AtomicBoolean sending = new AtomicBoolean();

        private Connection(UUID id, long userId, String sessionId, HttpSession session, SseEmitter emitter) {
            this.id = id;
            this.userId = userId;
            this.sessionId = sessionId;
            this.session = session;
            this.emitter = emitter;
        }
    }
}
