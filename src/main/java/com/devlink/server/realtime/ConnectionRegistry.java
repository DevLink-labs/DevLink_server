package com.devlink.server.realtime;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * 인증된 Agent별 현재 브로커 연결을 관리한다.
 * 연결 중 Agent 상태는 AGENT_PRESENCE로 보고하며, 단절·만료 시 서비스에서 OFFLINE 처리한다.
 */
@Component
public class ConnectionRegistry {
    private static final Logger log = LoggerFactory.getLogger(ConnectionRegistry.class);
    private static final CloseStatus CONNECTION_REPLACED =
            new CloseStatus(4002, "Connection replaced");

    private final ConcurrentMap<String, ConnectionState> connections = new ConcurrentHashMap<>();
    private final LongSupplier nanoTime;

    public ConnectionRegistry() {
        this(System::nanoTime);
    }

    // 경과 시간은 시스템 시계 변경의 영향을 받지 않는 단조 시계로 측정
    ConnectionRegistry(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    /**
     * 인증 과정에서 확인한 Agent ID와 열린 연결을 등록한다.
     * 새 연결을 먼저 등록한 뒤 기존 연결을 종료하므로 이전 종료 콜백이 새 연결을 지울 수 없다.
     */
    public void register(String agentId, WebSocketSession session) {
        Assert.hasText(agentId, "agentId must not be blank");
        Assert.notNull(session, "session must not be null");
        Assert.isTrue(session.isOpen(), "session must be open");

        ConnectionState[] previous = {null};
        connections.compute(agentId, (key, current) -> {
            previous[0] = current;
            // 첫 PING이 오지 않는 연결도 만료되도록 등록 시점부터 감시
            return new ConnectionState(session, nanoTime.getAsLong());
        });
        if (previous[0] != null && previous[0].session() != session) {
            closeReplacedConnection(agentId, previous[0].session());
        }
    }

    /** 현재 열린 브로커 연결을 반환한다. Agent의 실제 접속 상태를 뜻하지는 않는다. */
    public Optional<WebSocketSession> findByAgentId(String agentId) {
        Assert.hasText(agentId, "agentId must not be blank");
        return Optional.ofNullable(connections.get(agentId))
                .map(ConnectionState::session).filter(WebSocketSession::isOpen);
    }

    /** 정리 작업을 기다리지 않고 현재 연결의 하트비트 유효 여부를 읽는다. 연결 제거·소켓 종료는 하지 않는다. */
    public boolean hasActiveConnection(String agentId, Duration timeout) {
        Assert.hasText(agentId, "agentId must not be blank");
        Assert.notNull(timeout, "timeout must not be null");
        Assert.isTrue(!timeout.isZero() && !timeout.isNegative(), "timeout must be positive");
        long timeoutNanos = timeout.toNanos();
        boolean[] active = {false};
        // 재접속·PING 갱신과 같은 잠금에서 하나의 연결 상태를 기준으로 판정한다.
        connections.computeIfPresent(agentId, (key, current) -> {
            active[0] = current.session().isOpen()
                    && nanoTime.getAsLong() - current.lastPingNanos() < timeoutNanos;
            return current;
        });
        return active[0];
    }

    /** 이전 연결의 메시지가 새 연결에 영향을 주지 않도록 실제 세션 객체까지 비교한다. */
    public boolean isCurrentConnection(String agentId, WebSocketSession session) {
        Assert.hasText(agentId, "agentId must not be blank");
        Assert.notNull(session, "session must not be null");
        ConnectionState current = connections.get(agentId);
        return current != null && current.session() == session && session.isOpen();
    }

    /** 현재 연결 확인과 짧은 메모리 작업을 하나로 처리해, 확인 직후 연결이 바뀌는 틈을 막는다. */
    public boolean executeIfCurrent(String agentId, WebSocketSession session, BooleanSupplier action) {
        Assert.hasText(agentId, "agentId must not be blank");
        Assert.notNull(session, "session must not be null");
        Assert.notNull(action, "action must not be null");
        boolean[] executed = {false};
        connections.computeIfPresent(agentId, (key, current) -> {
            if (current.session() == session && session.isOpen()) {
                // 이 안에서는 ACK 대기 제거 같은 메모리 작업만 수행
                // 소켓 전송·결과 이벤트 통지·DB 접근은 맵 잠금 밖에서 수행
                executed[0] = action.getAsBoolean();
            }
            return current;
        });
        return executed[0];
    }

    /** 현재 연결의 유효한 JSON PING만 감시 시간을 연장한다. Agent 상태는 변경하지 않는다. */
    public boolean recordHeartbeat(String agentId, WebSocketSession session) {
        Assert.hasText(agentId, "agentId must not be blank");
        Assert.notNull(session, "session must not be null");
        boolean[] refreshed = {false};
        connections.computeIfPresent(agentId, (key, current) -> {
            if (current.session() == session && session.isOpen()) {
                refreshed[0] = true;
                return new ConnectionState(session, nanoTime.getAsLong());
            }
            return current;
        });
        return refreshed[0];
    }

    /** PING 갱신·재접속과 같은 잠금 안에서 만료를 판정하고 해당 연결만 제거한다. */
    public List<ExpiredConnection> removeExpiredConnections(Duration timeout) {
        Assert.notNull(timeout, "timeout must not be null");
        Assert.isTrue(!timeout.isZero() && !timeout.isNegative(), "timeout must be positive");
        long timeoutNanos = timeout.toNanos();
        List<ExpiredConnection> expired = new ArrayList<>();
        connections.forEach((agentId, ignored) -> connections.computeIfPresent(agentId, (key, current) -> {
            if (nanoTime.getAsLong() - current.lastPingNanos() >= timeoutNanos) {
                expired.add(new ExpiredConnection(agentId, current.session()));
                return null;
            }
            return current;
        }));
        // 소켓 종료·DB 갱신은 맵 잠금 밖에서 수행해 연결 등록과의 교착을 피함
        return expired;
    }

    /**
     * 종료된 연결이 현재 연결과 같을 때만 제거한다.
     * 교체된 이전 연결의 종료 이벤트라면 새 연결을 유지하고 false를 반환한다.
     */
    public boolean unregister(String agentId, WebSocketSession session) {
        Assert.hasText(agentId, "agentId must not be blank");
        Assert.notNull(session, "session must not be null");

        boolean[] removed = {false};
        connections.computeIfPresent(agentId, (key, current) -> {
            if (current.session() == session) {
                removed[0] = true;
                return null;
            }
            return current;
        });
        return removed[0];
    }

    private record ConnectionState(WebSocketSession session, long lastPingNanos) { }

    public record ExpiredConnection(String agentId, WebSocketSession session) { }

    private void closeReplacedConnection(String agentId, WebSocketSession previous) {
        if (!previous.isOpen()) {
            return;
        }
        try {
            previous.close(CONNECTION_REPLACED);
        } catch (IOException exception) {
            log.warn("Failed to close replaced broker connection for agent {}", agentId, exception);
        }
    }
}
