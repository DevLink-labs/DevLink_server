package com.devlink.server.service;

import java.util.UUID;
import com.devlink.server.domain.outbox.OutboxEvent;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 질문·답변과 같은 트랜잭션에서 상대 Agent에게 보낼 동기화 신호를 저장한다. */
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
        // event_id는 알림 재시도 식별자이고, payload.task_event_id가 원본 TaskEvent.id다.
        // 본문은 넣지 않는다. 브로커는 이 신호를 받으면 task-messages를 즉시 조회한다.
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
