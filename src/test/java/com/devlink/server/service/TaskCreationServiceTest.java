package com.devlink.server.service;

import static com.devlink.server.AckTestDependencies.transactionManager;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.dto.TaskRoutingResult;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskEventRepository;
import com.devlink.server.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

class TaskCreationServiceTest {
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
