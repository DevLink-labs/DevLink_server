package com.devlink.server.service;

import java.time.Duration;
import java.time.LocalDateTime;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.AgentStatus;
import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.repository.AgentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.web.socket.WebSocketSession;

@Service
public class AgentPresenceService {
    private final AgentRepository agents;
    private final ConnectionRegistry connections;
    private final ApplicationEventPublisher events;
    private final Duration heartbeatTimeout;

    public AgentPresenceService(AgentRepository agents, ConnectionRegistry connections,
            ApplicationEventPublisher events,
            @Value("${devlink.broker.heartbeat-timeout-ms:40000}") long timeoutMillis) {
        Assert.isTrue(timeoutMillis > 0, "heartbeat timeout must be positive");
        this.agents = agents;
        this.connections = connections;
        this.events = events;
        this.heartbeatTimeout = Duration.ofMillis(timeoutMillis);
    }

    /** 조회된 Agent의 상태와 현재 연결을 조합하며, 추가 DB 조회나 상태 갱신은 하지 않는다. */
    public AgentStatus connectionStatus(Agent agent) {
        Assert.notNull(agent, "agent must not be null");
        // 브로커만 연결되고 Agent가 ONLINE을 보고하지 않은 경우는 OFFLINE을 유지한다.
        return agent.getStatus() == AgentStatus.ONLINE
                && connections.hasActiveConnection(agent.getId().toString(), heartbeatTimeout)
                ? AgentStatus.ONLINE : AgentStatus.OFFLINE;
    }

    @Transactional
    public void updateStatus(AgentPrincipal principal, WebSocketSession session, AgentStatus status) {
        Assert.notNull(principal, "principal must not be null");
        Assert.notNull(session, "session must not be null");
        Assert.notNull(status, "status must not be null");
        Agent agent = agents.findByIdForPresenceUpdate(principal.agentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!connections.isCurrentConnection(principal.getName(), session)) {
            return;
        }
        if (!agent.getOwnerUserId().equals(principal.ownerUserId())
                || !agent.getTeamId().equals(principal.teamId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        if (status == AgentStatus.ONLINE) {
            agent.markOnline(LocalDateTime.now());
            // 커밋에 성공한 ONLINE 보고만 미전달 작업 조회를 시작한다.
            events.publishEvent(new AgentOnline(principal.agentId(), session));
        } else {
            agent.markOffline();
        }
    }

    @Transactional
    public void markOfflineIfDisconnected(long agentId) {
        agents.findByIdForPresenceUpdate(agentId).ifPresent(agent -> {
            if (connections.findByAgentId(Long.toString(agentId)).isEmpty()) {
                agent.markOffline();
            }
        });
    }

    record AgentOnline(long agentId, WebSocketSession session) { }
}
