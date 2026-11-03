package com.devlink.server.dto.task;

import java.time.Instant;
import java.time.ZoneOffset;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;

public record TaskMessageResponse(
        String eventId,
        Long taskId,
        String sequence,
        TaskEventType eventType,
        AuthorAgent authorAgent,
        String message,
        String questionEventId,
        Instant createdAt,
        boolean replayed) {

    public static TaskMessageResponse from(TaskEvent event, String authorName, boolean replayed) {
        Long questionEventId = event.getQuestionEventId();
        return new TaskMessageResponse(event.getId().toString(), event.getTaskId(),
                event.getSequence().toString(), event.getEventType(),
                new AuthorAgent(event.getAuthorAgentId(), authorName), event.getContent(),
                questionEventId == null ? null : questionEventId.toString(),
                event.getCreatedAt().toInstant(ZoneOffset.UTC), replayed);
    }

    public record AuthorAgent(Long agentId, String name) { }
}
