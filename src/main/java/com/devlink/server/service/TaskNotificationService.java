package com.devlink.server.service;

import java.io.IOException;
import java.util.Objects;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.dto.OutboxDelivery;
import com.devlink.server.dto.TaskNotificationResult;
import com.devlink.server.dto.TaskNotificationResult.Status;
import com.devlink.server.realtime.BrokerMessageSender;
import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskEventRepository;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TeamMemberRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/** 저장된 Outbox JSON을 알림 종류에 맞는 Agent에게 전송한다. 저장한 메시지를 다시 만들지 않는다. */
@Service
public class TaskNotificationService {
    private static final Logger log = LoggerFactory.getLogger(TaskNotificationService.class);
    private final TaskRepository tasks;
    private final AgentRepository agents;
    private final TaskEventRepository events;
    private final TeamMemberRepository members;
    private final ConnectionRegistry connections;
    private final BrokerMessageSender sender;
    private final AgentPresenceService presence;

    public TaskNotificationService(TaskRepository tasks, AgentRepository agents, TaskEventRepository events,
            TeamMemberRepository members, ConnectionRegistry connections,
            BrokerMessageSender sender, AgentPresenceService presence) {
        this.tasks = tasks;
        this.agents = agents;
        this.events = events;
        this.members = members;
        this.connections = connections;
        this.sender = sender;
        this.presence = presence;
    }

    @Transactional(propagation = Propagation.NEVER)
    public TaskNotificationResult sendNotification(OutboxDelivery delivery, Runnable beforeSend) {
        JsonNode message = delivery.message();
        long taskId = message.path("task_id").asLong();
        if (!validEnvelope(delivery, message)) {
            return result(delivery, taskId, Status.INVALID_EVENT);
        }
        var current = tasks.findById(taskId);
        boolean taskMessage = isTaskMessage(delivery.eventType());
        // 질문·답변은 Task 상태 변경이 아니므로 version이나 현재 진행 상태로 거르지 않는다.
        if (current.isEmpty() || (!taskMessage && current.get().getVersion() < message.path("version").longValue())) {
            return result(delivery, taskId, Status.INVALID_EVENT);
        }
        var task = current.get();
        if (taskMessage && !validTaskMessageReference(task, message)) {
            return result(delivery, taskId, Status.INVALID_EVENT);
        }
        // 상태·완료 알림은 기존 요청자 경로를 유지한다. 질문·답변만 저장된 수신자로 라우팅한다.
        long recipientId = taskMessage ? message.path("receiver_agent_id").longValue() : task.getRequesterAgentId();
        Status unavailable = taskMessage ? Status.RECIPIENT_UNAVAILABLE : Status.REQUESTER_UNAVAILABLE;
        var recipient = agents.findById(recipientId);
        if (recipient.isEmpty() || !recipient.get().getTeamId().equals(task.getTeamId())
                || (recipientId == task.getRequesterAgentId()
                    && !recipient.get().getOwnerUserId().equals(task.getRequesterUserId()))
                || (taskMessage && !members.existsByTeamIdAndUserId(task.getTeamId(), recipient.get().getOwnerUserId()))) {
            return result(delivery, taskId, unavailable);
        }
        String agentId = recipient.get().getId().toString();
        var connection = connections.findByAgentId(agentId);
        if (connection.isEmpty()) {
            return result(delivery, taskId, Status.CONNECTION_UNAVAILABLE);
        }
        WebSocketSession session = connection.get();
        if (!matchesRecipient(session, recipient.get())) {
            return result(delivery, taskId, unavailable);
        }
        try {
            // JSON 문자열을 다시 감싸지 않고 저장된 JsonNode 전체를 공통 전송기로 전달한다.
            // 실제 쓰기 직전 짧은 점유 연장 트랜잭션을 끝내고, 네트워크 전송에는 DB 잠금을 유지하지 않는다.
            boolean sent = sender.sendIfCurrent(agentId, session, message, beforeSend);
            return result(delivery, taskId, sent ? Status.SENT : Status.CONNECTION_CHANGED);
        } catch (IOException | IllegalStateException exception) {
            log.warn("Failed to publish notification outbox {}", delivery.outboxId(), exception);
            cleanUpConnection(agentId, recipientId, session);
            return result(delivery, taskId, Status.TRANSPORT_ERROR);
        }
    }

    private boolean validEnvelope(OutboxDelivery delivery, JsonNode message) {
        if (!"TASK".equals(delivery.aggregateType()) || !"task-events".equals(delivery.destination())
                || !message.isObject() || !delivery.outboxId().toString().equals(message.path("event_id").asText())
                || !delivery.eventType().equals(message.path("type").asText())
                || !message.path("task_id").isIntegralNumber() || !message.path("task_id").canConvertToLong()
                || message.path("task_id").longValue() <= 0 || !message.path("payload").isObject()) {
            return false;
        }
        String taskId = message.path("task_id").asText();
        if (!taskId.equals(delivery.aggregateId()) || !("TASK:" + taskId).equals(delivery.messageKey())) {
            return false;
        }
        if (isTaskMessage(delivery.eventType())) {
            // 질문·답변의 원본 ID와 순서는 JavaScript 정밀도 손실을 막는 양의 정수 문자열이다.
            JsonNode payload = message.path("payload");
            return message.path("receiver_agent_id").isIntegralNumber()
                    && message.path("receiver_agent_id").canConvertToLong()
                    && message.path("receiver_agent_id").longValue() > 0
                    && positiveLongText(payload.path("task_event_id"))
                    && positiveLongText(payload.path("sequence"))
                    && expectedMessageType(delivery.eventType()).name().equals(payload.path("event_type").asText());
        }
        // 기존 상태·완료 알림은 version 검증을 계속 적용한다.
        if (!message.path("version").isIntegralNumber() || !message.path("version").canConvertToLong()
                || message.path("version").longValue() < 0) {
            return false;
        }
        String status = message.path("payload").path("status").asText();
        if ("TASK_STATUS_CHANGED".equals(delivery.eventType())) {
            String previous = message.path("payload").path("previous_status").asText();
            return ("IN_PROGRESS".equals(status) || "REJECTED".equals(status)) && "PENDING".equals(previous)
                    || "FAILED".equals(status) && "IN_PROGRESS".equals(previous);
        }
        return "TASK_COMPLETED".equals(delivery.eventType()) && TaskStatus.COMPLETED.name().equals(status);
    }

    private boolean isTaskMessage(String type) {
        return "TASK_QUESTION_CREATED".equals(type) || "TASK_ANSWER_CREATED".equals(type);
    }

    private TaskEventType expectedMessageType(String type) {
        return "TASK_QUESTION_CREATED".equals(type) ? TaskEventType.QUESTION : TaskEventType.ANSWER;
    }

    private boolean positiveLongText(JsonNode value) {
        if (!value.isTextual() || !value.textValue().matches("[1-9][0-9]{0,18}")) {
            return false;
        }
        try {
            return Long.parseLong(value.textValue()) > 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean validTaskMessageReference(Task task, JsonNode message) {
        long recipientId = message.path("receiver_agent_id").longValue();
        if (task.getRequesterAgentId().equals(task.getReceiverAgentId())
                || (recipientId != task.getRequesterAgentId() && recipientId != task.getReceiverAgentId())) {
            return false;
        }
        long authorId = recipientId == task.getRequesterAgentId() ? task.getReceiverAgentId() : task.getRequesterAgentId();
        JsonNode payload = message.path("payload");
        // 잘못 저장된 수신자나 다른 Task의 이벤트를 그대로 신뢰해 전송하지 않는다.
        // 원본은 검증에만 사용하고, 재시도에서도 저장한 Outbox 메시지는 그대로 전송한다.
        return events.findById(Long.parseLong(payload.path("task_event_id").textValue()))
                .filter(event -> task.getId().equals(event.getTaskId())
                        && event.getEventType() == expectedMessageType(message.path("type").asText())
                        && Objects.equals(event.getSequence(), Long.parseLong(payload.path("sequence").textValue()))
                        && Objects.equals(event.getAuthorAgentId(), authorId))
                .isPresent();
    }

    private boolean matchesRecipient(WebSocketSession session, Agent recipient) {
        return session.getPrincipal() instanceof Authentication authentication
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AgentPrincipal principal
                && principal.agentId() == recipient.getId()
                && principal.ownerUserId() == recipient.getOwnerUserId()
                && principal.teamId() == recipient.getTeamId();
    }

    private TaskNotificationResult result(OutboxDelivery delivery, long taskId, Status status) {
        return new TaskNotificationResult(taskId, delivery.outboxId().toString(), status);
    }

    private void cleanUpConnection(String agentId, long recipientId, WebSocketSession session) {
        try {
            if (connections.unregister(agentId, session)) {
                presence.markOfflineIfDisconnected(recipientId);
            }
        } catch (RuntimeException exception) {
            log.error("Failed to mark disconnected recipient {} offline", agentId, exception);
        }
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.SERVER_ERROR);
            }
        } catch (IOException | RuntimeException exception) {
            log.warn("Failed to close recipient connection {}", agentId, exception);
        }
    }
}
