package com.devlink.server.dto.task;

import com.devlink.server.domain.task.TaskStatus;
import java.time.LocalDateTime;
import java.util.List;

public record TaskDetailResponse(
        Long taskId,
        Long teamId,
        String teamName,
        String taskSummary,
        TaskAgentResponse requesterAgent,
        TaskAgentResponse receiverAgent,
        String prompt,
        TaskStatus status,
        Long version,
        String resultText,
        String failureReason,
        String rejectionReason,
        boolean partiallyModified,
        List<TaskFileResponse> taskFiles,
        List<TaskEventResponse> events,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime lastActivityAt
) {
}