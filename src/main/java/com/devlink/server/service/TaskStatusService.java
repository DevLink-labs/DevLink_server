package com.devlink.server.service;

import java.util.UUID;
import com.devlink.server.domain.outbox.OutboxEvent;
import com.devlink.server.dto.TaskStatusChangedMessage;
import com.devlink.server.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.dto.ChangeTaskStatusRequest;
import com.devlink.server.dto.ChangeTaskStatusResponse;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TaskEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TaskStatusService {
    private final TaskRepository taskRepository;
    private final TaskEventRepository taskEventRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public ChangeTaskStatusResponse changeStatus(Long taskId, Long agentId, ChangeTaskStatusRequest request) {
        Task task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        if (!task.getReceiverAgentId().equals(agentId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        if (!task.getVersion().equals(request.version())) {
            throw new BusinessException(ErrorCode.TASK_VERSION_CONFLICT);
        }

        // 요청한 상태와 기대값을 비교
        TaskStatus previous = task.getStatus();
        TaskStatus expected = switch (request.status()) {
            case IN_PROGRESS, REJECTED -> TaskStatus.PENDING;
            case FAILED -> TaskStatus.IN_PROGRESS;
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        };
        if (previous != expected) {         // 허용되지 않은 상태전이는 예외
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }

        String description;
        switch (request.status()) {
            case IN_PROGRESS -> {
                task.accept(agentId);
                description = "작업 수락";
            }
            case REJECTED -> {
                task.reject(agentId, request.rejectionReason());
                description = request.rejectionReason();
            }
            case FAILED -> {
                task.fail(agentId, request.failureReason(), request.partiallyModified());
                description = request.failureReason();
            }
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        taskEventRepository.save(TaskEvent.of(taskId, TaskEventType.STATUS_CHANGED,
                previous + " → " + task.getStatus() + ": " + description));
        // 이력과 상태를 함께 flush한다. 동시 변경은 @Version으로 검출하고 전체 롤백한다.
        taskRepository.flush();
        UUID outboxId = UUID.randomUUID();
        var message = TaskStatusChangedMessage.from(outboxId, task, previous);
        // 별도 트랜잭션이나 커밋 후 콜백을 사용하지 않는다. 저장 실패 시 상태와 이력도 롤백된다.
        outboxEventRepository.save(OutboxEvent.create(outboxId, TaskStatusChangedMessage.TYPE,
                "TASK", taskId.toString(), TaskStatusChangedMessage.DESTINATION, "TASK:" + taskId,
                objectMapper.valueToTree(message)));
        return ChangeTaskStatusResponse.from(task);
    }
}
