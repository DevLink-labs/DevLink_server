package com.devlink.server.service;

import java.time.LocalDateTime;
import java.util.Optional;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.AgentStatus;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.repository.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentPresenceServiceTest {
    private final AgentRepository agents = mock(AgentRepository.class);
    private final ConnectionRegistry connections = new ConnectionRegistry();
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final AgentPresenceService service = new AgentPresenceService(agents, connections, events);
    private final AgentPrincipal principal = new AgentPrincipal(11, 21, 31);
    private final Agent agent = Agent.create(31L, 21L, "Codex", null, ToolType.CODEX, "server");

    @Test
    void onlineUpdatesStatusAndUsesServerTime() {
        WebSocketSession session = registerSession();
        when(agents.findByIdForPresenceUpdate(11L)).thenReturn(Optional.of(agent));
        LocalDateTime before = LocalDateTime.now();

        service.updateStatus(principal, session, AgentStatus.ONLINE);

        assertThat(agent.getStatus()).isEqualTo(AgentStatus.ONLINE);
        assertThat(agent.getLastSeenAt()).isBetween(before, LocalDateTime.now());
        verify(events).publishEvent(new AgentPresenceService.AgentOnline(11L, session));
    }

    @Test
    void offlinePreservesLastOnlineTimeAndCanBeRepeated() {
        WebSocketSession session = registerSession();
        when(agents.findByIdForPresenceUpdate(11L)).thenReturn(Optional.of(agent));
        LocalDateTime lastSeen = LocalDateTime.of(2026, 10, 27, 12, 0);
        agent.markOnline(lastSeen);

        service.updateStatus(principal, session, AgentStatus.OFFLINE);
        service.updateStatus(principal, session, AgentStatus.OFFLINE);

        assertThat(agent.getStatus()).isEqualTo(AgentStatus.OFFLINE);
        assertThat(agent.getLastSeenAt()).isEqualTo(lastSeen);
        verifyNoInteractions(events);
    }

    @Test
    void reportsMissingAgent() {
        WebSocketSession session = registerSession();
        when(agents.findByIdForPresenceUpdate(11L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus(principal, session, AgentStatus.ONLINE))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void rejectsChangedOwnership() {
        WebSocketSession session = registerSession();
        Agent anotherOwner = Agent.create(31L, 99L, "Other", null, ToolType.CODEX, "server");
        when(agents.findByIdForPresenceUpdate(11L)).thenReturn(Optional.of(anotherOwner));

        assertThatThrownBy(() -> service.updateStatus(principal, session, AgentStatus.ONLINE))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
        assertThat(anotherOwner.getStatus()).isEqualTo(AgentStatus.OFFLINE);
    }

    @Test
    void rejectsChangedTeam() {
        WebSocketSession session = registerSession();
        Agent anotherTeam = Agent.create(99L, 21L, "Other", null, ToolType.CODEX, "server");
        when(agents.findByIdForPresenceUpdate(11L)).thenReturn(Optional.of(anotherTeam));

        assertThatThrownBy(() -> service.updateStatus(principal, session, AgentStatus.ONLINE))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
        assertThat(anotherTeam.getStatus()).isEqualTo(AgentStatus.OFFLINE);
    }

    @Test
    void ignoresPresenceIfConnectionWasReplacedWhileWaitingForDatabaseLock() {
        WebSocketSession previous = registerSession();
        WebSocketSession current = openSession();
        when(agents.findByIdForPresenceUpdate(11L)).thenAnswer(invocation -> {
            connections.register("11", current);
            return Optional.of(agent);
        });

        service.updateStatus(principal, previous, AgentStatus.ONLINE);

        assertThat(agent.getStatus()).isEqualTo(AgentStatus.OFFLINE);
        assertThat(agent.getLastSeenAt()).isNull();
        verifyNoInteractions(events);
    }

    @Test
    void disconnectMarksAgentOfflineAndPreservesLastSeenTime() {
        LocalDateTime lastSeen = LocalDateTime.of(2026, 10, 27, 12, 0);
        agent.markOnline(lastSeen);
        when(agents.findByIdForPresenceUpdate(11L)).thenReturn(Optional.of(agent));

        service.markOfflineIfDisconnected(11L);

        assertThat(agent.getStatus()).isEqualTo(AgentStatus.OFFLINE);
        assertThat(agent.getLastSeenAt()).isEqualTo(lastSeen);
    }

    @Test
    void lateDisconnectCannotOverwriteReplacementPresence() {
        agent.markOnline(LocalDateTime.now());
        WebSocketSession replacement = openSession();
        when(agents.findByIdForPresenceUpdate(11L)).thenAnswer(invocation -> {
            connections.register("11", replacement);
            return Optional.of(agent);
        });

        service.markOfflineIfDisconnected(11L);

        assertThat(agent.getStatus()).isEqualTo(AgentStatus.ONLINE);
    }

    @Test
    void disconnectOfDeletedAgentIsHarmless() {
        when(agents.findByIdForPresenceUpdate(11L)).thenReturn(Optional.empty());

        service.markOfflineIfDisconnected(11L);
    }

    private WebSocketSession registerSession() {
        WebSocketSession session = openSession();
        connections.register("11", session);
        return session;
    }

    private WebSocketSession openSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
