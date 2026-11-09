package com.devlink.server.realtime;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectionRegistryTest {
    private final ConnectionRegistry registry = new ConnectionRegistry();

    @Test
    void activeConnectionExpiresAtBoundaryWithoutRemovingOrClosingSocket() throws Exception {
        AtomicLong clock = new AtomicLong();
        ConnectionRegistry timed = new ConnectionRegistry(clock::get);
        WebSocketSession session = openSession();
        timed.register("11", session);
        Duration timeout = Duration.ofMillis(2500);

        clock.set(timeout.toNanos() - 1);
        assertThat(timed.hasActiveConnection("11", timeout)).isTrue();
        clock.incrementAndGet();
        assertThat(timed.hasActiveConnection("11", timeout)).isFalse();
        // 추천용 조회는 연결을 남겨 기존 정리 작업이 나중에 종료할 수 있게 한다.
        assertThat(timed.findByAgentId("11")).contains(session);
        verify(session, never()).close(any(CloseStatus.class));
        assertThat(timed.removeExpiredConnections(timeout))
                .containsExactly(new ConnectionRegistry.ExpiredConnection("11", session));
    }

    @Test
    void activeConnectionRequiresAnOpenRegisteredSocket() {
        Duration timeout = Duration.ofSeconds(40);
        assertThat(registry.hasActiveConnection("11", timeout)).isFalse();
        WebSocketSession session = openSession();
        registry.register("11", session);
        assertThat(registry.hasActiveConnection("11", timeout)).isTrue();
        when(session.isOpen()).thenReturn(false);
        assertThat(registry.hasActiveConnection("11", timeout)).isFalse();
    }

    @Test
    void activeConnectionUsesLatestPingAndIgnoresOldConnectionEventsAfterReconnect() {
        AtomicLong clock = new AtomicLong();
        ConnectionRegistry timed = new ConnectionRegistry(clock::get);
        Duration timeout = Duration.ofSeconds(40);
        WebSocketSession previous = openSession();
        timed.register("11", previous);
        clock.set(Duration.ofSeconds(20).toNanos());
        timed.recordHeartbeat("11", previous);
        clock.set(Duration.ofSeconds(40).toNanos());
        assertThat(timed.hasActiveConnection("11", timeout)).isTrue();
        clock.set(Duration.ofSeconds(60).toNanos());
        assertThat(timed.hasActiveConnection("11", timeout)).isFalse();

        WebSocketSession current = openSession();
        timed.register("11", current);
        assertThat(timed.recordHeartbeat("11", previous)).isFalse();
        assertThat(timed.unregister("11", previous)).isFalse();
        assertThat(timed.hasActiveConnection("11", timeout)).isTrue();
        clock.set(Duration.ofSeconds(100).toNanos());
        assertThat(timed.hasActiveConnection("11", timeout)).isFalse();
    }

    @Test
    void firstPingMustArriveBeforeTimeoutAndExpiresAtBoundary() {
        AtomicLong clock = new AtomicLong();
        ConnectionRegistry timed = new ConnectionRegistry(clock::get);
        WebSocketSession session = openSession();
        timed.register("11", session);

        // 첫 PING이 없어도 등록 시점부터 40초를 계산하며 경계값에서 만료된다.
        clock.set(Duration.ofSeconds(40).toNanos() - 1);
        assertThat(timed.removeExpiredConnections(Duration.ofSeconds(40))).isEmpty();
        clock.incrementAndGet();
        assertThat(timed.removeExpiredConnections(Duration.ofSeconds(40)))
                .containsExactly(new ConnectionRegistry.ExpiredConnection("11", session));
        assertThat(timed.findByAgentId("11")).isEmpty();
    }

    @Test
    void validHeartbeatExtendsOnlyTheCurrentConnectionsDeadline() {
        AtomicLong clock = new AtomicLong();
        ConnectionRegistry timed = new ConnectionRegistry(clock::get);
        WebSocketSession session = openSession();
        timed.register("11", session);
        clock.set(Duration.ofSeconds(20).toNanos());
        assertThat(timed.recordHeartbeat("11", session)).isTrue();

        clock.set(Duration.ofSeconds(40).toNanos());
        assertThat(timed.removeExpiredConnections(Duration.ofSeconds(40))).isEmpty();
        clock.set(Duration.ofSeconds(60).toNanos());
        assertThat(timed.removeExpiredConnections(Duration.ofSeconds(40)))
                .containsExactly(new ConnectionRegistry.ExpiredConnection("11", session));
    }

    @Test
    void replacementStartsFreshDeadlineAndRejectsOldHeartbeat() {
        AtomicLong clock = new AtomicLong();
        ConnectionRegistry timed = new ConnectionRegistry(clock::get);
        WebSocketSession previous = openSession();
        WebSocketSession current = openSession();
        timed.register("11", previous);
        clock.set(Duration.ofSeconds(39).toNanos());
        timed.register("11", current);

        clock.set(Duration.ofSeconds(40).toNanos());
        assertThat(timed.recordHeartbeat("11", previous)).isFalse();
        assertThat(timed.removeExpiredConnections(Duration.ofSeconds(40))).isEmpty();
        assertThat(timed.findByAgentId("11")).contains(current);
        clock.set(Duration.ofSeconds(79).toNanos());
        assertThat(timed.removeExpiredConnections(Duration.ofSeconds(40)))
                .containsExactly(new ConnectionRegistry.ExpiredConnection("11", current));
    }

    @Test
    void expiredConnectionCannotBeRevivedByLatePing() {
        AtomicLong clock = new AtomicLong();
        ConnectionRegistry timed = new ConnectionRegistry(clock::get);
        WebSocketSession session = openSession();
        timed.register("11", session);
        clock.set(Duration.ofSeconds(40).toNanos());
        timed.removeExpiredConnections(Duration.ofSeconds(40));

        assertThat(timed.recordHeartbeat("11", session)).isFalse();
        assertThat(timed.findByAgentId("11")).isEmpty();
    }

    @Test
    void registersAndFindsAnOpenConnection() {
        WebSocketSession session = openSession();

        registry.register("agent_001", session);

        assertThat(registry.findByAgentId("agent_001")).contains(session);
        assertThat(registry.isCurrentConnection("agent_001", session)).isTrue();
        assertThat(registry.findByAgentId("missing_agent")).isEmpty();
    }

    @Test
    void replacingConnectionKeepsNewConnectionWhenOldCloseCallbackArrives() throws IOException {
        WebSocketSession previous = openSession();
        WebSocketSession current = openSession();
        registry.register("agent_001", previous);
        doAnswer(invocation -> {
            assertThat(registry.unregister("agent_001", previous)).isFalse();
            assertThat(registry.findByAgentId("agent_001")).contains(current);
            return null;
        }).when(previous).close(any(CloseStatus.class));

        registry.register("agent_001", current);

        verify(previous).close(new CloseStatus(4002, "Connection replaced"));
        assertThat(registry.isCurrentConnection("agent_001", previous)).isFalse();
        assertThat(registry.isCurrentConnection("agent_001", current)).isTrue();
    }

    @Test
    void failedCloseDoesNotRestoreReplacedConnection() throws IOException {
        WebSocketSession previous = openSession();
        WebSocketSession current = openSession();
        registry.register("agent_001", previous);
        doThrow(new IOException("Transport already unavailable"))
                .when(previous).close(any(CloseStatus.class));

        registry.register("agent_001", current);

        assertThat(registry.findByAgentId("agent_001")).contains(current);
        assertThat(registry.isCurrentConnection("agent_001", previous)).isFalse();
    }

    @Test
    void unregistersOnlyTheCurrentConnection() {
        WebSocketSession current = openSession();
        WebSocketSession unrelated = openSession();
        registry.register("agent_001", current);

        assertThat(registry.unregister("agent_001", unrelated)).isFalse();
        assertThat(registry.unregister("agent_001", current)).isTrue();
        assertThat(registry.unregister("agent_001", current)).isFalse();
        assertThat(registry.findByAgentId("agent_001")).isEmpty();
    }

    @Test
    void keepsDifferentAgentsConnectionsSeparate() {
        WebSocketSession first = openSession();
        WebSocketSession second = openSession();
        registry.register("agent_001", first);
        registry.register("agent_002", second);

        registry.unregister("agent_001", first);

        assertThat(registry.findByAgentId("agent_001")).isEmpty();
        assertThat(registry.findByAgentId("agent_002")).contains(second);
    }

    @Test
    void registeringTheSameSessionAgainDoesNotCloseIt() throws IOException {
        WebSocketSession session = openSession();

        registry.register("agent_001", session);
        registry.register("agent_001", session);

        verify(session, never()).close(any(CloseStatus.class));
        assertThat(registry.findByAgentId("agent_001")).contains(session);
    }

    @Test
    void doesNotReturnClosedConnectionsWhileWaitingForCloseCallback() {
        WebSocketSession session = openSession();
        registry.register("agent_001", session);
        when(session.isOpen()).thenReturn(false);

        assertThat(registry.findByAgentId("agent_001")).isEmpty();
        assertThat(registry.isCurrentConnection("agent_001", session)).isFalse();
        assertThat(registry.unregister("agent_001", session)).isTrue();
    }

    @Test
    void rejectsClosedRegistrationWithoutReplacingExistingConnection() {
        WebSocketSession current = openSession();
        WebSocketSession closed = mock(WebSocketSession.class);
        registry.register("agent_001", current);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry.register("agent_001", closed));

        assertThat(registry.findByAgentId("agent_001")).contains(current);
    }

    @Test
    void concurrentRegistrationsLeaveOneCurrentOpenConnection() throws Exception {
        int registrationCount = 12;
        CountDownLatch start = new CountDownLatch(1);
        List<WebSocketSession> sessions = new ArrayList<>();
        List<Future<?>> registrations = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (int index = 0; index < registrationCount; index++) {
                WebSocketSession session = openSession();
                AtomicBoolean open = new AtomicBoolean(true);
                when(session.isOpen()).thenAnswer(invocation -> open.get());
                doAnswer(invocation -> {
                    open.set(false);
                    return null;
                }).when(session).close(any(CloseStatus.class));
                sessions.add(session);
                registrations.add(executor.submit(() -> {
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Registration start timed out");
                    }
                    registry.register("agent_001", session);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> registration : registrations) {
                registration.get(10, TimeUnit.SECONDS);
            }
        }

        WebSocketSession current = registry.findByAgentId("agent_001").orElseThrow();
        assertThat(sessions.stream().filter(WebSocketSession::isOpen).toList())
                .containsExactly(current);
        for (WebSocketSession session : sessions) {
            assertThat(registry.isCurrentConnection("agent_001", session))
                    .isEqualTo(session == current);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingAgentIdentity(String agentId) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry.register(agentId, openSession()));
    }

    @Test
    void rejectsNullSession() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> registry.register("agent_001", null));
    }

    private WebSocketSession openSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
