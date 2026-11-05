package com.devlink.server.dto.task;

import com.devlink.server.domain.taskevent.TaskEventType;
import java.time.LocalDateTime;

public record TaskEventResponse(
		String eventId,
		String sequence,
        TaskEventType eventType,
        TaskAgentResponse authorAgent,
        String message,
        String questionEventId,
        LocalDateTime createdAt
) {
	
	
}