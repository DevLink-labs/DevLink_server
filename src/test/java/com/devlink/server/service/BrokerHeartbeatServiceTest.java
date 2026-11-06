package com.devlink.server.service;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.AgentStatus;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.repository.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

class BrokerHeartbeatServiceTest {
    private final ConnectionRegistry connections = mock(ConnectionRegistry.class);
    private final AgentPresenceService presence = mock(AgentPresenceService.class);
    private final TaskAckService acknowledgements = mock(TaskAckService.class);
    private final BrokerHeartbeatService heartbeat = new BrokerHeartbeatService(
            connections, presence, 40000, acknowledgements);

    @Test
    void timeoutClosesSocketAndChecksOfflineStateEvenWhenCloseFails() throws Exception {
        WebSocketSession first = session(11);
        WebSocketSession second = session(12);
        when(connections.removeExpiredConnections(Duration.ofSeconds(40))).thenReturn(List.of(
                new ConnectionRegistry.ExpiredConnection("11", first),
                new ConnectionRegistry.ExpiredConnection("12", second)));
        doThrow(new IOException("Lost transport")).when(first).close(any(CloseStatus.class));
        doThrow(new IllegalStateException("Temporary database failure")).when(presence)
                .markOfflineIfDisconnected(11L);

        // 한 연결의 종료·상태 갱신이 실패해도 나머지 만료 연결은 계속 정리한다.
        heartbeat.checkTimeouts();

        verify(first).close(new CloseStatus(4001, "Heartbeat timeout"));
        verify(second).close(new CloseStatus(4001, "Heartbeat timeout"));
        verify(presence).markOfflineIfDisconnected(11L);
        verify(presence).markOfflineIfDisconnected(12L);
    }

    @Test
    void delayedTimeoutCleanupOnlyClosesOldSocketAfterReconnect() throws Exception {
        ConnectionRegistry realConnections = new ConnectionRegistry();
        AgentRepository agents = mock(AgentRepository.class);
        AgentPresenceService realPresence = new AgentPresenceService(
                agents, realConnections, mock(org.springframework.context.ApplicationEventPublisher.class));
        BrokerHeartbeatService service = new BrokerHeartbeatService(
                connections, realPresence, 40000, acknowledgements);
        WebSocketSession previous = session(11);
        WebSocketSession current = session(11);
        Agent agent = Agent.create(31L, 21L, "Codex", null, ToolType.CODEX, "server");
        agent.markOnline(LocalDateTime.now());
        when(agents.findByIdForPresenceUpdate(11L)).thenReturn(Optional.of(agent));
        when(connections.removeExpiredConnections(Duration.ofSeconds(40))).thenReturn(
                List.of(new ConnectionRegistry.ExpiredConnection("11", previous)));
        doAnswer(invocation -> {
            // 만료된 소켓을 닫는 도중 새 브로커가 접속·ONLINE 보고를 마친 상황을 재현한다.
            realConnections.register("11", current);
            return null;
        }).when(previous).close(any(CloseStatus.class));

        service.checkTimeouts();

        assertThat(realConnections.findByAgentId("11")).contains(current);
        assertThat(agent.getStatus()).isEqualTo(AgentStatus.ONLINE);
        verify(previous).close(new CloseStatus(4001, "Heartbeat timeout"));
    }

    private WebSocketSession session(long agentId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getPrincipal()).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                new AgentPrincipal(agentId, 21, 31), null, List.of()));
        return session;
    }
}
