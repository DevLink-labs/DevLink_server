package com.devlink.server.service;

import java.util.List;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.dto.AgentResponse;
import com.devlink.server.dto.CreateAgentRequest;
import com.devlink.server.dto.UpdateAgentRequest;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.realtime.AgentCredentialsChangedEvent;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentService {
    private final AgentRepository agents;
    private final TeamAuthorizationService authorization;
    private final TaskRepository tasks;
    private final ApplicationEventPublisher events;

    public AgentService(AgentRepository agents, TeamAuthorizationService authorization,
            TaskRepository tasks, ApplicationEventPublisher events) {
        this.agents = agents;
        this.authorization = authorization;
        this.tasks = tasks;
        this.events = events;
    }

    @Transactional
    public AgentResponse create(Long userId, Long teamId, CreateAgentRequest request) {
        authorization.requireTeamMember(userId, teamId);
        Agent agent = Agent.create(teamId, userId, request.name(), request.roleDescription(),
                request.toolType(), request.projectName());
        return saveAgent(agent);
    }

    private AgentResponse saveAgent(Agent agent) {
        try {
            return AgentResponse.from(agents.saveAndFlush(agent));
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "uk_agents_team_name_tool_type".equals(violation.getConstraintName())) {
                    throw new BusinessException(ErrorCode.AGENT_ALREADY_EXISTS);
                }
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public List<AgentResponse> list(Long userId, Long teamId, boolean ownedByMe) {
        authorization.requireTeamMember(userId, teamId);
        List<Agent> teamAgents = ownedByMe
                ? agents.findUserAgentInTeam(teamId, userId)
                : agents.findAllByTeamIdOrderByIdAsc(teamId);
        return teamAgents.stream().map(AgentResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public AgentResponse get(Long userId, Long agentId) {
        requireUser(userId);
        requireAgentId(agentId);
        Agent agent = agents.findById(agentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        authorization.requireTeamMember(userId, agent.getTeamId());
        return AgentResponse.from(agent);
    }

    @Transactional
    public AgentResponse update(Long userId, Long agentId, UpdateAgentRequest request) {
        Agent agent = requireOwnedAgentForUpdate(userId, agentId);
        if ((request.name() != null && request.name().isBlank())
                || (request.projectName() != null && request.projectName().isBlank())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        agent.updateProfile(request.name(), request.roleDescription(), request.projectName());
        return saveAgent(agent);
    }

    @Transactional
    public void delete(Long userId, Long agentId) {
        Agent agent = requireOwnedAgentForUpdate(userId, agentId);
        // 기존 작업 조회에서 참조하는 Agent 정보 보존
        if (tasks.existsByRequesterAgentIdOrReceiverAgentId(agentId, agentId)) {
            throw new BusinessException(ErrorCode.AGENT_HAS_TASKS);
        }
        agents.delete(agent);
        agents.flush();
        // 삭제 커밋 후 기존 브로커 연결과 ACK 대기 정리
        events.publishEvent(new AgentCredentialsChangedEvent(agentId));
    }

    private Agent requireOwnedAgentForUpdate(Long userId, Long agentId) {
        requireUser(userId);
        requireAgentId(agentId);
        Agent agent = agents.findByIdForUpdate(agentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        authorization.requireTeamMember(userId, agent.getTeamId());
        if (!agent.getOwnerUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return agent;
    }

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
    }

    private void requireAgentId(Long agentId) {
        if (agentId == null || agentId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
