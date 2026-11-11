package com.devlink.server.service;

import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.AgentStatus;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskEventRepository;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TeamMemberRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebEventQueryServiceTest {
    private final TaskQueryService tasks = mock(TaskQueryService.class);
    private final TaskEventRepository events = mock(TaskEventRepository.class);
    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final AgentRepository agents = mock(AgentRepository.class);
    private final AgentPresenceService presence = mock(AgentPresenceService.class);
    private final TeamMemberRepository members = mock(TeamMemberRepository.class);
    private final WebEventQueryService queries = new WebEventQueryService(tasks, events, taskRepository,
            agents, presence, members);

    @Test
    void unrelatedUserReceivesNoTaskOrMessage() {
        var task = Task.create("task", 10L, 11L, 1L, 12L, "prompt");
        when(taskRepository.findById(20L)).thenReturn(Optional.of(task));
        when(members.existsByTeamIdAndUserId(10L, 99L)).thenReturn(false);
        assertThat(queries.task(99, 20L, null)).isNull();
        verify(tasks, never()).getWebTask(any(), any(), any());
    }

    @Test
    void internalBrokerEventsAreNotExposedAsMessages() {
        when(events.findById(21L)).thenReturn(Optional.of(TaskEvent.of(20L, TaskEventType.ACK_RECEIVED, "internal")));
        assertThat(queries.task(1, 20L, 21L)).isNull();
        verify(tasks, never()).getWebTask(any(), any(), any());
    }

    @Test
    void presenceIsRestrictedToCurrentTeamMembersAndUsesEffectiveConnectionStatus() {
        when(members.existsByTeamIdAndUserId(10L, 1L)).thenReturn(true);
        var agent = Agent.create(10L, 1L, "Agent", null, ToolType.CODEX, "project");
        when(agents.findById(11L)).thenReturn(Optional.of(agent));
        when(presence.connectionStatus(agent)).thenReturn(AgentStatus.OFFLINE);
        assertThat(queries.agent(1, 11L, 10L, false).status()).isEqualTo(AgentStatus.OFFLINE);
        assertThat(queries.agent(2, 11L, 10L, false)).isNull();
        assertThat(queries.agent(1, 11L, 10L, true).deleted()).isTrue();
    }
}
