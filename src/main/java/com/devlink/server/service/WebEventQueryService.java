package com.devlink.server.service;

import com.devlink.server.domain.UserPrincipal;
import com.devlink.server.domain.agent.AgentStatus;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.dto.AgentResponse;
import com.devlink.server.dto.task.TaskAgentResponse;
import com.devlink.server.dto.task.TaskEventResponse;
import com.devlink.server.dto.task.WebTaskDetailResponse;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskEventRepository;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TeamMemberRepository;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WebEventQueryService {
    private static final Set<TaskEventType> VISIBLE_EVENTS = Set.of(TaskEventType.CREATED,
            TaskEventType.STATUS_CHANGED, TaskEventType.PROGRESS, TaskEventType.QUESTION, TaskEventType.ANSWER);
    private final TaskQueryService tasks;
    private final TaskEventRepository events;
    private final TaskRepository taskRepository;
    private final AgentRepository agents;
    private final AgentPresenceService presence;
    private final TeamMemberRepository members;

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public TaskUpdate task(long userId, Long taskId, Long eventId) {
        var event = eventId == null ? null : events.findById(eventId).orElse(null);
        if (event != null && !VISIBLE_EVENTS.contains(event.getEventType())) return null;
        if (event != null && !taskId.equals(event.getTaskId())) return null;
        var persisted = taskRepository.findById(taskId).orElse(null);
        if (persisted == null || !members.existsByTeamIdAndUserId(persisted.getTeamId(), userId)) return null;
        var task = tasks.getWebTask(persisted.getTeamId(), taskId, new UserPrincipal(userId));
        TaskEventResponse message = null;
        if (event != null) {
            var author = event.getAuthorAgentId() == null ? null : agents.findById(event.getAuthorAgentId()).orElse(null);
            message = new TaskEventResponse(event.getId().toString(), event.getSequence().toString(),
                    event.getEventType(), author == null ? null : new TaskAgentResponse(author.getId(), author.getName()),
                    event.getContent(), event.getQuestionEventId() == null ? null : event.getQuestionEventId().toString(),
                    event.getCreatedAt());
        }
        return new TaskUpdate(task, message);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public AgentPresence agent(long userId, Long agentId, Long teamId, boolean deleted) {
        if (!members.existsByTeamIdAndUserId(teamId, userId)) return null;
        var agent = agents.findById(agentId).orElse(null);
        if (deleted || agent == null) return new AgentPresence(teamId, agentId, AgentStatus.OFFLINE, null, true, null);
        var status = presence.connectionStatus(agent);
        return new AgentPresence(teamId, agentId, status, agent.getLastSeenAt(), false, AgentResponse.from(agent, status));
    }

    public record TaskUpdate(WebTaskDetailResponse task, TaskEventResponse event) { }
    public record AgentPresence(Long teamId, Long agentId, AgentStatus status, LocalDateTime lastSeenAt,
            boolean deleted, AgentResponse agent) { }
}
