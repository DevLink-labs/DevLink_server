package com.devlink.server.service;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.AgentStatus;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.dto.TaskCreatedMessage;
import com.devlink.server.dto.TaskRoutingResult;
import com.devlink.server.dto.TaskRoutingResult.Status;
import com.devlink.server.realtime.BrokerMessageSender;
import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/** 커밋된 미전달 작업을 전송한다. ACK는 별도 서비스가 비동기로 확인한다. */
@Service
public class TaskRoutingService {
    private static final Logger log = LoggerFactory.getLogger(TaskRoutingService.class);
    private final TaskRepository tasks;
    private final AgentRepository agents;
    private final ConnectionRegistry connections;
    private final BrokerMessageSender sender;
    private final AgentPresenceService presence;
    private final TaskAckService acknowledgements;

    public TaskRoutingService(TaskRepository tasks, AgentRepository agents, ConnectionRegistry connections,
            BrokerMessageSender sender, AgentPresenceService presence, TaskAckService acknowledgements) {
        this.tasks = tasks;
        this.agents = agents;
        this.connections = connections;
        this.sender = sender;
        this.presence = presence;
        this.acknowledgements = acknowledgements;
    }

    public List<Task> findUndeliveredTasks(long receiverAgentId) {
        return tasks.findByReceiverAgentIdAndStatusAndDeliveredAtIsNullOrderByIdAsc(
                receiverAgentId, TaskStatus.PENDING);
    }

    // 저장 트랜잭션 안에서 직접 호출하면 거부
    @Transactional(propagation = Propagation.NEVER)
    public TaskRoutingResult sendTask(long taskId) {
        return sendTask(taskId, null);
    }

    @Transactional(propagation = Propagation.NEVER)
    TaskRoutingResult sendTask(long taskId, WebSocketSession expectedSession) {
        Assert.isTrue(taskId > 0, "taskId must be positive");
        Task task = findTask(taskId);
        if (task.getDeliveredAt() != null) {
            return result(taskId, Status.ALREADY_DELIVERED);
        }
        if (task.getStatus() != TaskStatus.PENDING) {
            return result(taskId, Status.TASK_NOT_PENDING);
        }
        Agent receiver = agents.findById(task.getReceiverAgentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        // 잘못 저장된 수신자 정보로 다른 팀에 작업 내용이 노출되지 않도록 방어
        if (!task.getTeamId().equals(receiver.getTeamId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        if (receiver.getStatus() != AgentStatus.ONLINE) {
            return result(taskId, Status.RECEIVER_OFFLINE);
        }
        String agentId = receiver.getId().toString();
        var current = connections.findByAgentId(agentId);
        if (current.isEmpty()) {
            return result(taskId, Status.CONNECTION_UNAVAILABLE);
        }
        WebSocketSession session = current.get();
        // 재접속 재전송은 ONLINE을 보고한 연결에만 보낸다.
        if (expectedSession != null && session != expectedSession) {
            return result(taskId, Status.CONNECTION_REPLACED);
        }
        // 토큰 인증 당시 신원과 현재 DB의 Agent·소유자·팀이 모두 일치해야 전달
        if (!matchesReceiver(session, receiver)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        acknowledgements.finishPreviousAttempt(taskId, session);
        String eventId = UUID.randomUUID().toString();
        var principal = new AgentPrincipal(receiver.getId(), receiver.getOwnerUserId(), receiver.getTeamId());
        try {
            // 같은 소켓의 전송 차례를 기다린 뒤 실제로 쓰기 직전에 ACK 대기를 등록
            boolean sent = sender.sendIfCurrent(agentId, session, TaskCreatedMessage.from(eventId, task),
                    () -> acknowledgements.register(taskId, eventId, principal, session,
                            () -> recheckTask(taskId)));
            // 전송 대기 중 교체된 연결이면 새 연결로 임의 재전송하지 않고 호출자에게 결과를 돌려줌
            return new TaskRoutingResult(taskId, sent ? Status.SENT : Status.CONNECTION_REPLACED,
                    eventId, session.getId());
        } catch (TaskAckService.AckInProgressException exception) {
            return result(taskId, Status.ACK_IN_PROGRESS);
        } catch (TaskAckService.ConnectionReplacedException exception) {
            return result(taskId, Status.CONNECTION_REPLACED);
        } catch (SkipDelivery exception) {
            return result(taskId, exception.status);
        } catch (IOException | IllegalStateException exception) {
            acknowledgements.sendFailed(eventId, session);
            log.warn("Task routing failed for task {} and event {}", taskId, eventId, exception);
            cleanUpFailedConnection(agentId, receiver.getId(), session);
            // TRANSPORT_ERROR를 확정 미수신으로 해석 X
            return new TaskRoutingResult(taskId, Status.TRANSPORT_ERROR, eventId, session.getId());
        }
    }

    public boolean isAwaitingAck(TaskRoutingResult routing) {
        return acknowledgements.isAwaitingAck(routing.taskId(), routing.eventId());
    }

    private void recheckTask(long taskId) {
        // 대기를 확보한 뒤 다시 읽어, 앞선 전송의 ACK가 그 사이 커밋된 경우를 건너뛴다.
        Task task = findTask(taskId);
        if (task.getDeliveredAt() != null) {
            throw new SkipDelivery(Status.ALREADY_DELIVERED);
        }
        if (task.getStatus() != TaskStatus.PENDING) {
            throw new SkipDelivery(Status.TASK_NOT_PENDING);
        }
    }

    private Task findTask(long taskId) {
        return tasks.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private static final class SkipDelivery extends RuntimeException {
        private final Status status;

        private SkipDelivery(Status status) {
            this.status = status;
        }
    }

    private boolean matchesReceiver(WebSocketSession session, Agent receiver) {
        return session.getPrincipal() instanceof Authentication authentication
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AgentPrincipal principal
                && principal.agentId() == receiver.getId()
                && principal.ownerUserId() == receiver.getOwnerUserId()
                && principal.teamId() == receiver.getTeamId();
    }

    private TaskRoutingResult result(long taskId, Status status) {
        return new TaskRoutingResult(taskId, status, null, null);
    }

    private void cleanUpFailedConnection(String agentId, long receiverId, WebSocketSession session) {
        try {
            if (connections.unregister(agentId, session)) {
                presence.markOfflineIfDisconnected(receiverId);
            }
        } catch (RuntimeException exception) {
            log.error("Failed to mark disconnected agent {} offline", agentId, exception);
        }
        try {
            // 새 연결이 생겼더라도 옛 소켓만 닫는다.
            if (session.isOpen()) {
                session.close(CloseStatus.SERVER_ERROR);
            }
        } catch (IOException | RuntimeException exception) {
            log.warn("Failed to close broker after routing error for agent {}", agentId, exception);
        }
    }
}
