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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentService {
    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final AgentRepository agents;
    private final TeamAuthorizationService authorization;
    private final TaskRepository tasks;
    private final ApplicationEventPublisher events;
    private final AgentPresenceService presence;

    public AgentService(AgentRepository agents, TeamAuthorizationService authorization,
            TaskRepository tasks, ApplicationEventPublisher events, AgentPresenceService presence) {
        this.agents = agents;
        this.authorization = authorization;
        this.tasks = tasks;
        this.events = events;
        this.presence = presence;
    }

    @Transactional
    public AgentResponse create(Long userId, Long teamId, CreateAgentRequest request) {
        authorization.requireTeamMember(userId, teamId);
        Agent agent = Agent.create(teamId, userId, request.name(), request.roleDescription(),
                request.toolType(), request.projectName(), request.part(), normalizeRoles(request.roles()));
        return saveAgent(agent);
    }

    private AgentResponse saveAgent(Agent agent) {
        try {
            return AgentResponse.from(agents.saveAndFlush(agent));
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "uk_agents_team_name_tool_type".equals(violation.getConstraintName())) {
                    log.debug("Agent save rejected: reason=duplicate_agent, teamId={}, userId={}, agentId={}",
                            agent.getTeamId(), agent.getOwnerUserId(), agent.getId());
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
        return teamAgents.stream()
                .map(agent -> AgentResponse.from(agent, presence.connectionStatus(agent)))
                .toList();
    }

    @Transactional(readOnly = true)
    public AgentResponse get(Long userId, Long agentId) {
        requireUser(userId);
        requireAgentId(agentId);
        Agent agent = agents.findById(agentId)
                .orElseThrow(() -> {
                    log.debug("Agent lookup failed: reason=agent_not_found, userId={}, agentId={}", userId, agentId);
                    return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
                });
        authorization.requireTeamMember(userId, agent.getTeamId());
        return AgentResponse.from(agent, presence.connectionStatus(agent));
    }

    @Transactional
    public AgentResponse update(Long userId, Long agentId, UpdateAgentRequest request) {
        Agent agent = requireOwnedAgentForUpdate(userId, agentId);
        if ((request.name() != null && request.name().isBlank())
                || (request.projectName() != null && request.projectName().isBlank())) {
            log.debug("Agent update rejected: reason=blank_profile_field, userId={}, agentId={}", userId, agentId);
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        agent.updateProfile(request.name(), request.roleDescription(), request.projectName());
        agent.updateRoutingMetadata(request.part(), normalizeRoles(request.roles()));
        return saveAgent(agent);
    }

    @Transactional
    public void delete(Long userId, Long agentId) {
        Agent agent = requireOwnedAgentForUpdate(userId, agentId);
        // 기존 작업 조회에서 참조하는 Agent 정보 보존
        if (tasks.existsByRequesterAgentIdOrReceiverAgentId(agentId, agentId)) {
            log.debug("Agent deletion rejected: reason=task_history_exists, userId={}, agentId={}", userId, agentId);
            throw new BusinessException(ErrorCode.AGENT_HAS_TASKS);
        }
        try {
            agents.delete(agent);
            agents.flush();
        } catch (DataIntegrityViolationException exception) {
            if (TaskAgentForeignKeys.isViolatedBy(exception)) {
                log.debug("Agent deletion rejected: reason=task_reference_conflict, userId={}, agentId={}",
                        userId, agentId);
                throw new BusinessException(ErrorCode.AGENT_HAS_TASKS);
            }
            throw exception;
        }
        // 삭제 커밋 후 기존 브로커 연결과 ACK 대기 정리
        events.publishEvent(new AgentCredentialsChangedEvent(agentId));
    }

    private Agent requireOwnedAgentForUpdate(Long userId, Long agentId) {
        requireUser(userId);
        requireAgentId(agentId);
        Agent agent = agents.findByIdForUpdate(agentId)
                .orElseThrow(() -> {
                    log.debug("Agent change rejected: reason=agent_not_found, userId={}, agentId={}", userId, agentId);
                    return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
                });
        authorization.requireTeamMember(userId, agent.getTeamId());
        if (!agent.getOwnerUserId().equals(userId)) {
            log.debug("Agent change rejected: reason=owner_required, userId={}, teamId={}, agentId={}",
                    userId, agent.getTeamId(), agentId);
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return agent;
    }

    private List<String> normalizeRoles(List<String> roles) {
        if (roles == null) {
            return null;
        }
        if (roles.size() > 10 || roles.stream()
                .anyMatch(role -> role == null || role.isBlank() || role.length() > 64)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return roles.stream().map(String::trim).distinct().toList();
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
