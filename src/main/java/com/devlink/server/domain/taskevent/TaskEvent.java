package com.devlink.server.domain.taskevent;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;

// Bounded context: task.
@Entity
@EntityListeners(com.devlink.server.realtime.WebEventEntityListener.class)
@Table(name = "task_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", length = 255, nullable = false)
    private Long id;

    @Column(name = "task_id", length = 255, nullable = false)
    private Long taskId;

    @Generated
    @Column(name = "sequence", nullable = false, insertable = false, updatable = false)
    private Long sequence;

    @Column(name = "author_agent_id")
    private Long authorAgentId;

    @Column(name = "question_event_id")
    private Long questionEventId;

    @Column(name = "request_key", length = 64)
    private String requestKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 30, nullable = false)
    private TaskEventType eventType;

    @Column(name = "content", length = 255, nullable = true, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", length = 255, nullable = false, columnDefinition = "TIMESTAMP(3)")
    private LocalDateTime createdAt;

    public static TaskEvent of(Long taskId, TaskEventType type, String content) {
        return of(taskId, type, content, null, null, null);
    }

    public static TaskEvent of(Long taskId, TaskEventType type, String content,
            Long authorAgentId, Long questionEventId, String requestKey) {
        requireValue(taskId, "taskId");
        requireValue(type, "eventType");
        if (type == TaskEventType.QUESTION || type == TaskEventType.ANSWER) {
            requireValue(authorAgentId, "authorAgentId");
            requireValue(requestKey, "requestKey");
            if (requestKey.length() > 64) {
                throw new IllegalArgumentException("requestKey must be at most 64 characters");
            }
        }
        if ((type == TaskEventType.ANSWER) != (questionEventId != null)) {
            throw new IllegalArgumentException("questionEventId is required only for ANSWER");
        }
        TaskEvent entity = new TaskEvent();
        entity.taskId = taskId;
        entity.eventType = type;
        entity.content = content;
        entity.authorAgentId = authorAgentId;
        entity.questionEventId = questionEventId;
        entity.requestKey = requestKey;
        return entity;
    }

    @PrePersist
    private void initializeTimestamp() {
        this.createdAt = eventType == TaskEventType.QUESTION || eventType == TaskEventType.ANSWER
                ? LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS)
                : LocalDateTime.now();
    }

    private static void requireValue(Object value, String field) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
    }
}
