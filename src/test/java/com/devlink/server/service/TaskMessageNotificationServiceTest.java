package com.devlink.server.service;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.dto.OutboxDelivery;
import com.devlink.server.dto.TaskNotificationResult.Status;
import com.devlink.server.realtime.BrokerMessageSender;
import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskEventRepository;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TeamMemberRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 알림에 적힌 수신자와 원본 이벤트가 일치할 때만 상대 소켓에 전송하는지 검증한다. */
class TaskMessageNotificationServiceTest {
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final AgentRepository agents = mock(AgentRepository.class);
    private final TaskEventRepository events = mock(TaskEventRepository.class);
    private final TeamMemberRepository members = mock(TeamMemberRepository.class);
    private final AgentPresenceService presence = mock(AgentPresenceService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConnectionRegistry connections = new ConnectionRegistry();
    private final TaskNotificationService notifications = new TaskNotificationService(tasks, agents, events, members,
            connections, new BrokerMessageSender(connections, mapper), presence);
    private final Task task = Task.create("request", 31L, 1L, 20L, 2L, "private prompt");
    private TaskEvent event;

    TaskMessageNotificationServiceTest() {
        ReflectionTestUtils.setField(task, "id", 7L);
        ReflectionTestUtils.setField(task, "version", 0L);
        when(tasks.findById(7L)).thenReturn(Optional.of(task));
        for (long id = 1; id <= 2; id++) {
            Agent agent = Agent.create(31L, 19L + id, "Agent" + id, null, ToolType.CODEX, "server");
            ReflectionTestUtils.setField(agent, "id", id);
            when(agents.findById(id)).thenReturn(Optional.of(agent));
            when(members.existsByTeamIdAndUserId(31L, 19L + id)).thenReturn(true);
        }
    }

    @ParameterizedTest
    @CsvSource({"QUESTION,1,2", "QUESTION,2,1", "ANSWER,1,2", "ANSWER,2,1"})
    void sendsVersionlessStoredEnvelopeOnlyToOppositeParticipant(TaskEventType type, long authorId, long recipientId)
            throws Exception {
        var delivery = delivery(type, authorId, recipientId);
        var author = register(authorId);
        var recipient = register(recipientId);

        assertThat(notifications.sendNotification(delivery, () -> { }).status()).isEqualTo(Status.SENT);

        var sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(recipient).sendMessage(sent.capture());
        verify(author, never()).sendMessage(any());
        assertThat(mapper.readTree(sent.getValue().getPayload())).isEqualTo(mapper.readTree(delivery.message().toString()));
        assertThat(delivery.message().has("version")).isFalse();
        assertThat(sent.getValue().getPayload()).doesNotContain("private prompt", "private body");
    }

    @ParameterizedTest
    @ValueSource(strings = {"receiver-missing", "receiver-string", "receiver-overflow", "receiver-zero",
            "event-number", "event-zero", "event-negative", "event-overflow", "event-leading-zero",
            "sequence-number", "sequence-zero", "sequence-overflow", "kind-mismatch"})
    void malformedMessageIsRejectedBeforeDatabaseRouting(String mutation) throws Exception {
        var delivery = delivery(TaskEventType.QUESTION, 1, 2);
        var message = (ObjectNode) delivery.message();
        var payload = (ObjectNode) message.path("payload");
        switch (mutation) {
            case "receiver-missing" -> message.remove("receiver_agent_id");
            case "receiver-string" -> message.put("receiver_agent_id", "2");
            case "receiver-overflow" -> message.put("receiver_agent_id", new java.math.BigInteger("9223372036854775808"));
            case "receiver-zero" -> message.put("receiver_agent_id", 0);
            case "event-number" -> payload.put("task_event_id", 502);
            case "event-zero" -> payload.put("task_event_id", "0");
            case "event-negative" -> payload.put("task_event_id", "-1");
            case "event-overflow" -> payload.put("task_event_id", "9223372036854775808");
            case "event-leading-zero" -> payload.put("task_event_id", "0502");
            case "sequence-number" -> payload.put("sequence", 13);
            case "sequence-zero" -> payload.put("sequence", "0");
            case "sequence-overflow" -> payload.put("sequence", "9223372036854775808");
            case "kind-mismatch" -> payload.put("event_type", "ANSWER");
            default -> throw new IllegalArgumentException(mutation);
        }
        var recipient = register(2);

        assertThat(notifications.sendNotification(withMessage(delivery, message), () -> { }).status()).isEqualTo(Status.INVALID_EVENT);

        verify(recipient, never()).sendMessage(any());
        verify(tasks, never()).findById(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"unrelated-recipient", "author-as-recipient", "self-assigned", "missing-event",
            "wrong-task", "wrong-sequence", "wrong-type", "wrong-author"})
    void incorrectRecipientOrOriginalEventIsNeverSent(String mutation) throws Exception {
        var delivery = delivery(TaskEventType.QUESTION, 1, 2);
        var message = (ObjectNode) delivery.message();
        switch (mutation) {
            case "unrelated-recipient" -> message.put("receiver_agent_id", 99);
            case "author-as-recipient" -> message.put("receiver_agent_id", 1);
            case "self-assigned" -> ReflectionTestUtils.setField(task, "receiverAgentId", 1L);
            case "missing-event" -> when(events.findById(502L)).thenReturn(Optional.empty());
            case "wrong-task" -> ReflectionTestUtils.setField(event, "taskId", 8L);
            case "wrong-sequence" -> ReflectionTestUtils.setField(event, "sequence", 14L);
            case "wrong-type" -> ReflectionTestUtils.setField(event, "eventType", TaskEventType.ANSWER);
            case "wrong-author" -> ReflectionTestUtils.setField(event, "authorAgentId", 99L);
            default -> throw new IllegalArgumentException(mutation);
        }
        var requester = register(1);
        var recipient = register(2);

        assertThat(notifications.sendNotification(withMessage(delivery, message), () -> { }).status()).isEqualTo(Status.INVALID_EVENT);

        verify(requester, never()).sendMessage(any());
        verify(recipient, never()).sendMessage(any());
    }

    @ParameterizedTest
    @CsvSource({"99,21,31", "2,99,31", "2,21,99"})
    void recipientSessionMustMatchAgentOwnerAndTeam(long agentId, long ownerId, long teamId) throws Exception {
        var delivery = delivery(TaskEventType.QUESTION, 1, 2);
        var recipient = register(2);
        when(recipient.getPrincipal()).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                new AgentPrincipal(agentId, ownerId, teamId), null, List.of()));

        assertThat(notifications.sendNotification(delivery, () -> { }).status()).isEqualTo(Status.RECIPIENT_UNAVAILABLE);

        verify(recipient, never()).sendMessage(any());
    }

    @Test
    void removedMemberCannotReceiveMessageThroughExistingSocket() throws Exception {
        var delivery = delivery(TaskEventType.QUESTION, 1, 2);
        var recipient = register(2);
        when(members.existsByTeamIdAndUserId(31L, 21L)).thenReturn(false);

        assertThat(notifications.sendNotification(delivery, () -> { }).status()).isEqualTo(Status.RECIPIENT_UNAVAILABLE);

        verify(recipient, never()).sendMessage(any());
    }

    @Test
    void failedWriteCleansUpRecipientInsteadOfRequester() throws Exception {
        var delivery = delivery(TaskEventType.QUESTION, 1, 2);
        var author = register(1);
        var recipient = register(2);
        doThrow(new IOException("write failed")).when(recipient).sendMessage(any());

        assertThat(notifications.sendNotification(delivery, () -> { }).status()).isEqualTo(Status.TRANSPORT_ERROR);

        assertThat(connections.findByAgentId("1")).contains(author);
        assertThat(connections.findByAgentId("2")).isEmpty();
        verify(presence).markOfflineIfDisconnected(2L);
    }

    private OutboxDelivery withMessage(OutboxDelivery original, ObjectNode message) {
        // OutboxDelivery는 메시지의 방어적 복사를 반환하므로 변경한 봉투로 별도 전달 객체를 만든다.
        return new OutboxDelivery(original.outboxId(), original.lockToken(), original.eventType(), original.aggregateType(),
                original.aggregateId(), original.destination(), original.messageKey(), message, original.attemptCount());
    }

    private OutboxDelivery delivery(TaskEventType type, long authorId, long recipientId) {
        event = TaskEvent.of(7L, type, "private body", authorId, type == TaskEventType.ANSWER ? 501L : null, "key");
        ReflectionTestUtils.setField(event, "id", 502L);
        ReflectionTestUtils.setField(event, "sequence", 13L);
        when(events.findById(502L)).thenReturn(Optional.of(event));
        UUID id = UUID.randomUUID();
        String notificationType = type == TaskEventType.QUESTION ? "TASK_QUESTION_CREATED" : "TASK_ANSWER_CREATED";
        var message = mapper.createObjectNode().put("event_id", id.toString()).put("type", notificationType)
                .put("task_id", 7).put("receiver_agent_id", recipientId);
        message.putObject("payload").put("task_event_id", "502").put("sequence", "13").put("event_type", type.name());
        return new OutboxDelivery(id, UUID.randomUUID(), notificationType, "TASK", "7", "task-events", "TASK:7", message, 1);
    }

    private WebSocketSession register(long agentId) {
        var session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("agent-" + agentId);
        when(session.getPrincipal()).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                new AgentPrincipal(agentId, 19 + agentId, 31), null, List.of()));
        connections.register(Long.toString(agentId), session);
        return session;
    }
}
