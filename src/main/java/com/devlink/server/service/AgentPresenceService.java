package com.devlink.server.service;

import java.time.LocalDateTime;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.AgentStatus;
import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.repository.AgentRepository;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.web.socket.WebSocketSession;

@Service
public class AgentPresenceService {
    private final AgentRepository agents;
    private final ConnectionRegistry connections;
    private final ApplicationEventPublisher events;

    public AgentPresenceService(AgentRepository agents, ConnectionRegistry connections,
            ApplicationEventPublisher events) {
        this.agents = agents;
        this.connections = connections;
        this.events = events;
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
