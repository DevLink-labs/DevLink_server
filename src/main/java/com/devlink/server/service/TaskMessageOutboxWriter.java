package com.devlink.server.service;

import java.util.UUID;
import com.devlink.server.domain.outbox.OutboxEvent;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TaskMessageOutboxWriter {
    private static final String DESTINATION = "task-events";

    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;

    public void enqueue(TaskEvent event, Long receiverAgentId) {
        String type = switch (event.getEventType()) {
            case QUESTION -> "TASK_QUESTION_CREATED";
            case ANSWER -> "TASK_ANSWER_CREATED";
            default -> throw new IllegalArgumentException("Expected QUESTION or ANSWER");
        };
        UUID outboxId = UUID.randomUUID();
        var envelope = objectMapper.createObjectNode()
                .put("event_id", outboxId.toString())
                .put("type", type)
                .put("task_id", event.getTaskId())
                .put("receiver_agent_id", receiverAgentId);
        envelope.putObject("payload")
                .put("task_event_id", event.getId().toString())
                .put("sequence", event.getSequence().toString())
                .put("event_type", event.getEventType().name());

        String taskId = event.getTaskId().toString();
        outbox.save(OutboxEvent.create(outboxId, type, "TASK", taskId, DESTINATION, "TASK:" + taskId, envelope));
    }
}
