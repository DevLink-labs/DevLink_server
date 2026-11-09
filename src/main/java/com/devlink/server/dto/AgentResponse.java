package com.devlink.server.dto;

import java.time.LocalDateTime;
import java.util.List;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.AgentPart;
import com.devlink.server.domain.agent.AgentStatus;
import com.devlink.server.domain.agent.ToolType;

public record AgentResponse(Long agentId, Long teamId, Long ownerUserId, String name,
        String roleDescription, ToolType toolType, String projectName, AgentStatus status,
        LocalDateTime lastSeenAt, LocalDateTime createdAt, AgentPart part, List<String> roles) {
    public static AgentResponse from(Agent agent) {
        return new AgentResponse(agent.getId(), agent.getTeamId(), agent.getOwnerUserId(),
                agent.getName(), agent.getRoleDescription(), agent.getToolType(), agent.getProjectName(),
                agent.getStatus(), agent.getLastSeenAt(), agent.getCreatedAt(), agent.getPart(), agent.getRoles());
    }
}
