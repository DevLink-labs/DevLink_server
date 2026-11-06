package com.devlink.server.service;

import static com.devlink.server.AckTestDependencies.transactionManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.dto.TaskRoutingResult;
import com.devlink.server.dto.CreateTaskRequest;
import com.devlink.server.dto.TaskDeliveryResult;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskEventRepository;
import com.devlink.server.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.springframework.web.socket.WebSocketSession;

class TaskCreationServiceTest {
    @ParameterizedTest
    @NullSource
    @EnumSource(value = TaskEventType.class, names = {"ACK_PENDING", "ACK_RECEIVED", "DELIVERY_UNKNOWN"})
    void ackInProgressUsesPendingOnlyWhenDeliveryHistoryIsMissing(TaskEventType storedType) {
        TaskRepository tasks = mock(TaskRepository.class);
        TaskEventRepository history = mock(TaskEventRepository.class);
        TaskRoutingService routing = mock(TaskRoutingService.class);
        AgentRepository agents = mock(AgentRepository.class);
        var service = new TaskCreationService(tasks, history, agents, routing, transactionManager());
        Agent agent = mock(Agent.class);
        when(agent.getTeamId()).thenReturn(31L);
        when(agent.getOwnerUserId()).thenReturn(21L);
        when(agents.findById(11L)).thenReturn(Optional.of(agent));
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(7L);
        when(task.getStatus()).thenReturn(TaskStatus.PENDING);
        when(tasks.save(any(Task.class))).thenReturn(task);
        when(tasks.findByIdForUpdate(7L)).thenReturn(Optional.of(task));
        when(routing.sendTask(7L)).thenReturn(new TaskRoutingResult(
                7L, TaskRoutingResult.Status.ACK_IN_PROGRESS, null, null));

        String reason = storedType == TaskEventType.DELIVERY_UNKNOWN ? "ACK_TIMEOUT" : null;
        Optional<TaskEvent> stored = Optional.empty();
        if (storedType != null) {
            TaskEvent event = mock(TaskEvent.class);
            when(event.getEventType()).thenReturn(storedType);
            when(event.getContent()).thenReturn(reason);
            stored = Optional.of(event);
        }
        // 전송 전에는 이력이 없고, 응답을 만들 때 다른 경로의 결과가 저장될 수도 있다.
        when(history.findFirstByTaskIdAndEventTypeInOrderByIdDesc(eq(7L), any()))
                .thenReturn(Optional.empty()).thenReturn(stored);

        var response = service.create(31L, 11L, new CreateTaskRequest("request-key", 11L, "Implement feature"));

        assertThat(response.delivery()).isEqualTo(new TaskDeliveryResult(
                storedType == null ? TaskEventType.ACK_PENDING : storedType, reason));
        verify(routing).sendTask(7L);
        verify(history).save(argThat(event -> event.getEventType() == TaskEventType.CREATED));
        verify(history, never()).save(argThat(event -> event.getEventType() != TaskEventType.CREATED));
    }

    @Test
    void replacedRetryDoesNotAppendFailureAfterCurrentConnectionsAckPending() {
        TaskRepository tasks = mock(TaskRepository.class);
        TaskEventRepository history = mock(TaskEventRepository.class);
        TaskRoutingService routing = mock(TaskRoutingService.class);
        var service = new TaskCreationService(tasks, history, mock(AgentRepository.class),
                routing, transactionManager());
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(7L);
        when(tasks.findByIdForUpdate(7L)).thenReturn(Optional.of(task));
        WebSocketSession previousSession = mock(WebSocketSession.class);
        when(routing.findUndeliveredTasks(11L)).thenReturn(List.of(task));

        // 이전 연결의 처리가 재개될 때 새 연결의 ACK_PENDING이 이미 최신 이력이다.
        TaskEvent currentAttempt = mock(TaskEvent.class);
        when(currentAttempt.getId()).thenReturn(100L);
        when(currentAttempt.getEventType()).thenReturn(TaskEventType.ACK_PENDING);
        when(history.findFirstByTaskIdAndEventTypeInOrderByIdDesc(eq(7L), any()))
                .thenReturn(Optional.of(currentAttempt));
        when(routing.sendTask(7L, previousSession)).thenReturn(new TaskRoutingResult(
                7L, TaskRoutingResult.Status.CONNECTION_REPLACED, null, null));

        service.retryUndelivered(11L, previousSession);

        verify(routing).sendTask(7L, previousSession);
        verify(history, never()).save(any(TaskEvent.class));
    }
}
