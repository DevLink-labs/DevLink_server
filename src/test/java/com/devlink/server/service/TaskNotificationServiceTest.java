package com.devlink.server.service;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import com.devlink.server.config.OutboxPublisherSettings;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.domain.task.Task;
import com.devlink.server.dto.OutboxDelivery;
import com.devlink.server.dto.TaskNotificationResult.Status;
import com.devlink.server.realtime.BrokerMessageSender;
import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TaskEventRepository;
import com.devlink.server.repository.TeamMemberRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TaskNotificationServiceTest {
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final AgentRepository agents = mock(AgentRepository.class);
    private final ConnectionRegistry connections = new ConnectionRegistry();
    private final ObjectMapper mapper = new ObjectMapper();
    private final BrokerMessageSender sender = new BrokerMessageSender(connections, mapper);
    private final AgentPresenceService presence = mock(AgentPresenceService.class);
    private final TaskNotificationService notifications = new TaskNotificationService(
            tasks, agents, mock(TaskEventRepository.class), mock(TeamMemberRepository.class), connections, sender, presence);
    private final Task task = Task.create("private-key", 31L, 1L, 20L, 2L, "private-prompt");
    private final Agent requester = Agent.create(31L, 20L, "Requester", null, ToolType.CODEX, "server");

    TaskNotificationServiceTest() {
        ReflectionTestUtils.setField(task, "id", 7L);
        ReflectionTestUtils.setField(task, "version", 2L);
        task.accept(2L);
        ReflectionTestUtils.setField(requester, "id", 1L);
        when(tasks.findById(7L)).thenReturn(Optional.of(task));
        when(agents.findById(1L)).thenReturn(Optional.of(requester));
    }

    @ParameterizedTest
    @CsvSource({"PENDING,IN_PROGRESS", "PENDING,REJECTED", "IN_PROGRESS,FAILED"})
    void sendsStoredEnvelopeWithSameIdVersionAndPayload(String previous, String status) throws Exception {
        var delivery = delivery(previous, status);
        WebSocketSession session = register();
        AtomicBoolean renewed = new AtomicBoolean();
        doAnswer(call -> {
            assertThat(renewed).isTrue();
            return null;
        }).when(session).sendMessage(any());

        assertThat(notifications.sendNotification(delivery, () -> renewed.set(true)).status()).isEqualTo(Status.SENT);

        var message = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(message.capture());
        assertThat(mapper.readTree(message.getValue().getPayload())).isEqualTo(delivery.message());
        assertThat(message.getValue().getPayload()).doesNotContain("private-key", "private-prompt", "requester_user_id");
        assertThat(task.getVersion()).isEqualTo(2L);
        verify(tasks, never()).save(any());
        verifyNoInteractions(presence);
    }

    @Test
    void laterTaskCompletionCannotRewriteStoredEarlierMessage() throws Exception {
        var delivery = delivery("PENDING", "IN_PROGRESS");
        task.complete(2L, "later result");
        WebSocketSession session = register();

        notifications.sendNotification(delivery, () -> { });

        var message = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(message.capture());
        assertThat(mapper.readTree(message.getValue().getPayload()).path("payload").path("status").asText())
                .isEqualTo("IN_PROGRESS");
        assertThat(mapper.readTree(message.getValue().getPayload()).path("version").asLong()).isEqualTo(1);
    }

    @Test
    void disconnectedRequesterReturnsRetryableOutcome() {
        assertThat(notifications.sendNotification(delivery("PENDING", "IN_PROGRESS"), () -> { }).status())
                .isEqualTo(Status.CONNECTION_UNAVAILABLE);
    }

    @ParameterizedTest
    @CsvSource({"99,20,31", "1,99,31", "1,20,99"})
    void sessionIdentityMustMatchRequester(long agentId, long ownerId, long teamId) throws Exception {
        WebSocketSession session = register();
        when(session.getPrincipal()).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                new AgentPrincipal(agentId, ownerId, teamId), null, List.of()));

        assertThat(notifications.sendNotification(delivery("PENDING", "IN_PROGRESS"), () -> { }).status())
                .isEqualTo(Status.REQUESTER_UNAVAILABLE);

        verify(session, never()).sendMessage(any());
    }

    @Test
    void changedAgentOwnershipCannotReceiveHistoricalTaskMessage() throws Exception {
        WebSocketSession session = register();
        ReflectionTestUtils.setField(requester, "ownerUserId", 99L);

        assertThat(notifications.sendNotification(delivery("PENDING", "IN_PROGRESS"), () -> { }).status())
                .isEqualTo(Status.REQUESTER_UNAVAILABLE);

        verify(session, never()).sendMessage(any());
    }

    @Test
    void wrongAggregateOrFutureVersionIsNotSent() throws Exception {
        WebSocketSession session = register();
        var valid = delivery("PENDING", "IN_PROGRESS");
        var invalid = new OutboxDelivery(valid.outboxId(), valid.lockToken(), valid.eventType(), "TASK", "8",
                valid.destination(), "TASK:8", valid.message(), 1);
        assertThat(notifications.sendNotification(invalid, () -> { }).status()).isEqualTo(Status.INVALID_EVENT);
        ReflectionTestUtils.setField(task, "version", 0L);
        assertThat(notifications.sendNotification(valid, () -> { }).status()).isEqualTo(Status.INVALID_EVENT);
        verify(session, never()).sendMessage(any());
    }

    @Test
    void sendFailurePreservesTaskAndDoesNotCloseNewReplacement() throws Exception {
        WebSocketSession previous = register();
        WebSocketSession current = session("new");
        doAnswer(call -> {
            connections.register("1", current);
            throw new IOException("old connection failed");
        }).when(previous).sendMessage(any());

        assertThat(notifications.sendNotification(delivery("PENDING", "IN_PROGRESS"), () -> { }).status())
                .isEqualTo(Status.TRANSPORT_ERROR);

        assertThat(connections.findByAgentId("1")).contains(current);
        verify(current, never()).close(any(CloseStatus.class));
        verifyNoInteractions(presence);
    }

    @Test
    void failedWriteReleasesCurrentConnection() throws Exception {
        WebSocketSession session = register();
        doThrow(new IOException("write failed")).when(session).sendMessage(any());

        assertThat(notifications.sendNotification(delivery("PENDING", "IN_PROGRESS"), () -> { }).status())
                .isEqualTo(Status.TRANSPORT_ERROR);

        assertThat(connections.findByAgentId("1")).isEmpty();
        verify(presence).markOfflineIfDisconnected(1L);
    }

    @Test
    void exponentialRetryIsCappedWithoutOverflow() {
        var settings = new OutboxPublisherSettings(false, 1, 1000, 1, 10, 10);
        assertThat(settings.retryDelayMillis(1)).isEqualTo(1);
        assertThat(settings.retryDelayMillis(2)).isEqualTo(2);
        assertThat(settings.retryDelayMillis(3)).isEqualTo(4);
        assertThat(settings.retryDelayMillis(5)).isEqualTo(10);
        assertThat(settings.retryDelayMillis(100)).isEqualTo(10);
    }

    private OutboxDelivery delivery(String previous, String status) {
        UUID id = UUID.randomUUID();
        var message = mapper.createObjectNode().put("type", "TASK_STATUS_CHANGED")
                .put("event_id", id.toString()).put("task_id", 7).put("version", 1);
        message.putObject("payload").put("previous_status", previous).put("status", status);
        return new OutboxDelivery(id, UUID.randomUUID(), "TASK_STATUS_CHANGED", "TASK", "7",
                "task-events", "TASK:7", message, 1);
    }

    private WebSocketSession register() {
        WebSocketSession session = session("requester");
        connections.register("1", session);
        return session;
    }

    private WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn(id);
        when(session.getPrincipal()).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                new AgentPrincipal(1, 20, 31), null, List.of()));
        return session;
    }
}
