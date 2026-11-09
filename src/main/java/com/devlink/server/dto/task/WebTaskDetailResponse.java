package com.devlink.server.dto.task;

import com.devlink.server.domain.task.TaskStatus;
import java.time.LocalDateTime;

public record WebTaskDetailResponse(
		Long taskId,
		Long teamId,
		String teamName,
		String taskSummary,
		TaskAgentResponse requesterAgent,
		TaskAgentResponse receiverAgent,
		TaskStatus status,
		LocalDateTime createdAt,
		LocalDateTime startedAt,
		LocalDateTime completedAt,
		LocalDateTime lastActivityAt) {

}
