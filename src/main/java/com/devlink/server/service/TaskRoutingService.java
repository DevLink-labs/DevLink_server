package com.devlink.server.service;

import java.io.IOException;
import java.util.UUID;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.AgentStatus;
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

/** 커밋된 작업을 한 번 전송한다. ACK는 별도 서비스가 비동기로 확인하며 자동 재전송하지 않는다. */
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

    /** 신규 작업 저장 전의 짧은 연결 검사. 소켓 전송과 ACK 등록은 커밋 이후에만 한다. */
    public void requireReceiverAvailable(Agent receiver) {
        if (receiver.getStatus() != AgentStatus.ONLINE) {
            throw new BusinessException(ErrorCode.RECEIVER_OFFLINE);
        }
        String agentId = receiver.getId().toString();
        try {
            WebSocketSession session = connections.findByAgentId(agentId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RECEIVER_OFFLINE));
            boolean available = connections.executeIfCurrent(agentId, session, () -> {
                if (!matchesReceiver(session, receiver)) {
                    throw new BusinessException(ErrorCode.ACCESS_DENIED);
                }
                return true;
            });
            if (!available) {
                throw new BusinessException(ErrorCode.RECEIVER_OFFLINE);
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.DELIVERY_SERVICE_UNAVAILABLE);
        }
        // 검사 후 단절될 수 있으므로 기존 커밋 후 전달 결과와 PENDING 보존 정책도 유지한다.
    }

    // 저장 트랜잭션 안에서 직접 호출하면 거부
    @Transactional(propagation = Propagation.NEVER)
    public TaskRoutingResult sendTask(long taskId) {
        Assert.isTrue(taskId > 0, "taskId must be positive");
        var task = tasks.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
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
        // 토큰 인증 당시 신원과 현재 DB의 Agent·소유자·팀이 모두 일치해야 전달
        if (!matchesReceiver(session, receiver)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        String eventId = UUID.randomUUID().toString();
        try {
            // 같은 소켓의 전송 차례를 기다린 뒤 실제로 쓰기 직전에 ACK 대기를 등록
            boolean sent = sender.sendIfCurrent(agentId, session, TaskCreatedMessage.from(eventId, task),
                    () -> acknowledgements.register(taskId, eventId,
                            new AgentPrincipal(receiver.getId(), receiver.getOwnerUserId(), receiver.getTeamId()),
                            session));
            // 전송 대기 중 교체된 연결이면 새 연결로 임의 재전송하지 않고 호출자에게 결과를 돌려줌
            return new TaskRoutingResult(taskId, sent ? Status.SENT : Status.CONNECTION_REPLACED,
                    eventId, session.getId());
        } catch (IOException | IllegalStateException exception) {
            acknowledgements.sendFailed(eventId, session);
            log.warn("Task routing failed for task {} and event {}", taskId, eventId, exception);
            cleanUpFailedConnection(agentId, receiver.getId(), session);
            // TRANSPORT_ERROR를 확정 미수신으로 해석 X
            return new TaskRoutingResult(taskId, Status.TRANSPORT_ERROR, eventId, session.getId());
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
