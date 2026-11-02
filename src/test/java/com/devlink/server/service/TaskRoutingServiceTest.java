package com.devlink.server.service;

import static com.devlink.server.AckTestDependencies.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.dto.TaskRoutingResult.Status;
import com.devlink.server.dto.TaskAckResult;
import com.devlink.server.realtime.BrokerMessageSender;
import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TaskRoutingServiceTest {
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final AgentRepository agents = mock(AgentRepository.class);
    private final ConnectionRegistry connections = new ConnectionRegistry();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AgentPresenceService presence = mock(AgentPresenceService.class);
    private final BrokerMessageSender sender = new BrokerMessageSender(connections, mapper);
    private final ApplicationEventPublisher ackEvents = mock(ApplicationEventPublisher.class);
    private final TaskAckService acknowledgements = new TaskAckService(connections, ackEvents, tasks(), history(), transactionManager(), 5000);
    private final TaskRoutingService routing = new TaskRoutingService(
            tasks, agents, connections, sender, presence, acknowledgements);
    private final Task task = Task.create("request-key", 31L, 1L, 20L, 2L, "파일을 수정해 주세요");
    private final Agent receiver = Agent.create(31L, 21L, "Receiver", null, ToolType.CODEX, "server");

    TaskRoutingServiceTest() {
        ReflectionTestUtils.setField(task, "id", 7L);
        ReflectionTestUtils.setField(task, "createdAt", LocalDateTime.of(2026, 10, 28, 10, 0));
        ReflectionTestUtils.setField(receiver, "id", 2L);
        receiver.markOnline(LocalDateTime.now());
        when(tasks.findById(7L)).thenReturn(Optional.of(task));
        when(agents.findById(2L)).thenReturn(Optional.of(receiver));
    }

    @Test
    void ackArrivingInsideSocketWriteIsAlreadyRegistered() throws Exception {
        WebSocketSession session = registerSession();
        doAnswer(invocation -> {
            var event = mapper.readTree(((TextMessage) invocation.getArgument(0)).getPayload());
            assertThat(acknowledgements.acknowledge(new AgentPrincipal(2, 21, 31), session, 7,
                    event.path("event_id").asText())).isTrue();
            return null;
        }).when(session).sendMessage(any());

        assertThat(routing.sendTask(7L).status()).isEqualTo(Status.SENT);

        var result = ArgumentCaptor.forClass(TaskAckResult.class);
        verify(ackEvents).publishEvent(result.capture());
        assertThat(result.getValue().status()).isEqualTo(TaskAckResult.Status.ACKNOWLEDGED);
        assertThat(task.getDeliveredAt()).isNull();
    }

    @Test
    void sendsOnlyRequiredTaskDataWithoutRecordingDeliveryOrChangingTask() throws Exception {
        WebSocketSession session = registerSession();

        var result = routing.sendTask(7L);

        assertThat(result.status()).isEqualTo(Status.SENT);
        assertThat(result.eventId()).isNotBlank();
        assertThat(result.connectionId()).isEqualTo("connection-2");
        var message = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(message.capture());
        var event = mapper.readTree(message.getValue().getPayload());
        assertThat(event.path("type").asText()).isEqualTo("TASK_CREATED");
        assertThat(event.path("event_id").asText()).isEqualTo(result.eventId());
        assertThat(event.path("payload").path("task_id").asLong()).isEqualTo(7);
        assertThat(event.path("payload").path("receiver_agent_id").asLong()).isEqualTo(2);
        assertThat(event.path("payload").path("prompt").asText()).isEqualTo(task.getPrompt());
        assertThat(message.getValue().getPayload()).doesNotContain("request-key", "token", "result_text");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.getDeliveredAt()).isNull();
        verify(tasks, never()).save(any());
        verifyNoInteractions(presence);
    }

    @Test
    void missingTaskDoesNotSendAnything() {
        when(tasks.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> routing.sendTask(7L)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        verifyNoInteractions(agents, presence);
    }

    @Test
    void taskAlreadyAcceptedIsNotRoutedAgain() throws Exception {
        WebSocketSession session = registerSession();
        task.accept(2L);

        assertThat(routing.sendTask(7L).status()).isEqualTo(Status.TASK_NOT_PENDING);

        verify(session, never()).sendMessage(any());
    }

    @Test
    void offlineAgentIsNotRoutedEvenWithOpenBrokerConnection() throws Exception {
        WebSocketSession session = registerSession();
        receiver.markOffline();

        assertThat(routing.sendTask(7L).status()).isEqualTo(Status.RECEIVER_OFFLINE);

        verify(session, never()).sendMessage(any());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void staleOnlineDatabaseStatusWithoutSocketDoesNotAllowRouting() {
        assertThat(routing.sendTask(7L).status()).isEqualTo(Status.CONNECTION_UNAVAILABLE);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void closedSocketIsNotUsed() throws Exception {
        WebSocketSession session = registerSession();
        when(session.isOpen()).thenReturn(false);

        assertThat(routing.sendTask(7L).status()).isEqualTo(Status.CONNECTION_UNAVAILABLE);

        verify(session, never()).sendMessage(any());
    }

    @Test
    void receiverFromAnotherTeamCannotReceiveTask() throws Exception {
        WebSocketSession session = registerSession();
        ReflectionTestUtils.setField(receiver, "teamId", 99L);

        assertThatThrownBy(() -> routing.sendTask(7L)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));

        verify(session, never()).sendMessage(any());
    }

    @ParameterizedTest
    @CsvSource({"99,21,31", "2,99,31", "2,21,99"})
    void connectionIdentityMustMatchAgentOwnerAndTeam(long agentId, long ownerId, long teamId) throws Exception {
        WebSocketSession session = registerSession();
        when(session.getPrincipal()).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                new AgentPrincipal(agentId, ownerId, teamId), null, List.of()));

        assertThatThrownBy(() -> routing.sendTask(7L)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));

        verify(session, never()).sendMessage(any());
    }

    @Test
    void sendFailureCleansUpConnectionButPreservesTask() throws Exception {
        WebSocketSession session = registerSession();
        doThrow(new IOException("Transport failed")).when(session).sendMessage(any());

        assertThat(routing.sendTask(7L).status()).isEqualTo(Status.TRANSPORT_ERROR);

        assertThat(connections.findByAgentId("2")).isEmpty();
        verify(presence).markOfflineIfDisconnected(2L);
        verify(session).close(CloseStatus.SERVER_ERROR);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.getDeliveredAt()).isNull();
    }

    @Test
    void failedOldSendDoesNotRemoveOrMarkReplacementOffline() throws Exception {
        WebSocketSession previous = registerSession();
        WebSocketSession current = session("replacement");
        doAnswer(invocation -> {
            connections.register("2", current);
            throw new IOException("Old socket failed after reconnect");
        }).when(previous).sendMessage(any());

        assertThat(routing.sendTask(7L).status()).isEqualTo(Status.TRANSPORT_ERROR);

        assertThat(connections.findByAgentId("2")).contains(current);
        verifyNoInteractions(presence);
        verify(current, never()).sendMessage(any());
    }

    @Test
    void replacementWhilePreparingMessageReturnsResultWithoutRetry() throws Exception {
        WebSocketSession previous = registerSession();
        WebSocketSession current = session("replacement");
        BrokerMessageSender replacingSender = new BrokerMessageSender(connections, mapper) {
            @Override
            public boolean sendIfCurrent(String agentId, WebSocketSession session, Object event, Runnable beforeSend)
                    throws IOException {
                connections.register(agentId, current);
                return super.sendIfCurrent(agentId, session, event, beforeSend);
            }
        };
        TaskRoutingService service = new TaskRoutingService(
                tasks, agents, connections, replacingSender, presence, acknowledgements);

        assertThat(service.sendTask(7L).status()).isEqualTo(Status.CONNECTION_REPLACED);

        verify(previous, never()).sendMessage(any());
        verify(current, never()).sendMessage(any());
    }

    @Test
    void preflightChecksCurrentIdentityWithoutSendingOrRegisteringAck() throws Exception {
        WebSocketSession session = registerSession();
        routing.requireReceiverAvailable(receiver);
        verify(session, never()).sendMessage(any());
        verifyNoInteractions(presence, ackEvents);
        verify(tasks, never()).save(any());
    }

    @Test
    void preflightRejectsOfflineEvenWithOpenConnection() {
        registerSession();
        receiver.markOffline();
        assertPreflightError(routing, ErrorCode.RECEIVER_OFFLINE);
    }

    @Test
    void preflightRejectsStaleOnlineWithoutConnection() {
        assertPreflightError(routing, ErrorCode.RECEIVER_OFFLINE);
    }

    @Test
    void preflightRejectsClosedConnection() {
        WebSocketSession session = registerSession();
        when(session.isOpen()).thenReturn(false);
        assertPreflightError(routing, ErrorCode.RECEIVER_OFFLINE);
    }

    @ParameterizedTest
    @CsvSource({"99,21,31", "2,99,31", "2,21,99"})
    void preflightRejectsWrongAgentOwnerOrTeam(long agentId, long ownerId, long teamId) {
        WebSocketSession session = registerSession();
        when(session.getPrincipal()).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                new AgentPrincipal(agentId, ownerId, teamId), null, List.of()));
        assertPreflightError(routing, ErrorCode.ACCESS_DENIED);
    }

    @Test
    void preflightRejectsConnectionReplacedDuringLookup() {
        WebSocketSession session = session("previous");
        ConnectionRegistry registry = mock(ConnectionRegistry.class);
        when(registry.findByAgentId("2")).thenReturn(Optional.of(session));
        when(registry.executeIfCurrent(org.mockito.ArgumentMatchers.eq("2"),
                org.mockito.ArgumentMatchers.eq(session), any())).thenReturn(false);
        var service = new TaskRoutingService(tasks, agents, registry, sender, presence, acknowledgements);
        assertPreflightError(service, ErrorCode.RECEIVER_OFFLINE);
    }

    @Test
    void preflightMapsRegistryFailureToServiceUnavailable() {
        ConnectionRegistry registry = mock(ConnectionRegistry.class);
        when(registry.findByAgentId("2")).thenThrow(new IllegalStateException("internal secret"));
        var service = new TaskRoutingService(tasks, agents, registry, sender, presence, acknowledgements);
        assertPreflightError(service, ErrorCode.DELIVERY_SERVICE_UNAVAILABLE);
    }

    private void assertPreflightError(TaskRoutingService service, ErrorCode error) {
        assertThatThrownBy(() -> service.requireReceiverAvailable(receiver))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(error));
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        verifyNoInteractions(presence, ackEvents);
    }

    private WebSocketSession registerSession() {
        WebSocketSession session = session("connection-2");
        connections.register("2", session);
        return session;
    }

    private WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn(id);
        when(session.getPrincipal()).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                new AgentPrincipal(2, 21, 31), null, List.of()));
        return session;
    }
}
