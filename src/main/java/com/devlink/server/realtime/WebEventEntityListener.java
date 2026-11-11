package com.devlink.server.realtime;

import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.teammember.TeamMember;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class WebEventEntityListener {
    private final ApplicationEventPublisher publisher;

    public WebEventEntityListener(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @PostPersist
    @PostUpdate
    public void changed(Object entity) {
        if (entity instanceof TaskEvent event) {
            publisher.publishEvent(new TaskChanged(event.getTaskId(), event.getId()));
        } else if (entity instanceof Task task) {
            publisher.publishEvent(new TaskChanged(task.getId(), null));
        } else if (entity instanceof Agent agent) {
            publisher.publishEvent(new AgentChanged(agent.getId(), agent.getTeamId(), false));
        } else if (entity instanceof TeamMember member) {
            publisher.publishEvent(new MembershipChanged(member.getUserId()));
        }
    }

    @PostRemove
    public void removed(Object entity) {
        if (entity instanceof Agent agent) {
            publisher.publishEvent(new AgentChanged(agent.getId(), agent.getTeamId(), true));
        } else if (entity instanceof TeamMember member) {
            publisher.publishEvent(new MembershipChanged(member.getUserId()));
        }
    }

    public record TaskChanged(Long taskId, Long eventId) { }
    public record AgentChanged(Long agentId, Long teamId, boolean deleted) { }
    public record MembershipChanged(Long userId) { }
}
