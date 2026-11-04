package com.devlink.server.domain.task;

import java.time.LocalDateTime;

import com.devlink.server.exception.InvalidTaskInputException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Bounded context: task.
@Entity
@Table(name = "tasks", uniqueConstraints = @UniqueConstraint(name = "uk_tasks_request_key", columnNames = {"requester_agent_id", "request_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Task {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", length = 255, nullable = false)
    private Long id;

    @Column(name = "request_key", length = 64, nullable = false)
    private String requestKey;

    @Column(name = "team_id", length = 255, nullable = false)
    private Long teamId;

    @Column(name = "requester_agent_id", length = 255, nullable = false)
    private Long requesterAgentId;

    @Column(name = "requester_user_id", length = 255, nullable = false)
    private Long requesterUserId;

    @Column(name = "receiver_agent_id", length = 255, nullable = false)
    private Long receiverAgentId;

    @Column(name = "prompt", length = 255, nullable = false, columnDefinition = "TEXT")
    private String prompt;

    // 등록 시 요약. 구버전 요청은 null이며 질문·답변·완료로 변경하지 않는다.
    @Column(name = "task_summary", length = 255, nullable = true, columnDefinition = "TEXT")
    private String taskSummary;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private TaskStatus status;

    @Column(name = "result_text", length = 255, nullable = true, columnDefinition = "TEXT")
    private String resultText;

    @Column(name = "partially_modified", length = 255, nullable = false)
    private boolean partiallyModified;

    @Version
    @Column(name = "version", length = 255, nullable = false)
    private Long version;

    @Column(name = "created_at", length = 255, nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "delivered_at", length = 255, nullable = true)
    private LocalDateTime deliveredAt;

    @Column(name = "started_at", length = 255, nullable = true)
    private LocalDateTime startedAt;

    @Column(name = "completed_at", length = 255, nullable = true)
    private LocalDateTime completedAt;

    public static Task create(String requestKey, Long teamId, Long requesterAgentId, Long requesterUserId, Long receiverAgentId, String prompt) {
        return create(requestKey, teamId, requesterAgentId, requesterUserId, receiverAgentId, prompt, null);
    }

    /** taskSummary는 호출자가 앞뒤 공백을 제거해 전달한다. null은 요약 미등록이다. */
    public static Task create(String requestKey, Long teamId, Long requesterAgentId, Long requesterUserId, Long receiverAgentId, String prompt, String taskSummary) {
        requireValue(requestKey, "requestKey");
        requireValue(teamId, "teamId");
        requireValue(requesterAgentId, "requesterAgentId");
        requireValue(requesterUserId, "requesterUserId");
        requireValue(receiverAgentId, "receiverAgentId");
        requireValue(prompt, "prompt");
        if (taskSummary != null) {
            requireValue(taskSummary, "taskSummary");
        }
        Task entity = new Task();
        entity.requestKey = requestKey;
        entity.teamId = teamId;
        entity.requesterAgentId = requesterAgentId;
        entity.requesterUserId = requesterUserId;
        entity.receiverAgentId = receiverAgentId;
        entity.prompt = prompt;
        entity.taskSummary = taskSummary;
        entity.status = TaskStatus.PENDING;
        return entity;
    }

    public void markDelivered(LocalDateTime at) {
        requireValue(at, "deliveredAt");
        this.deliveredAt = at;
    }

    public void accept(Long agentId) {
        requireReceiverAndStatus(agentId, TaskStatus.PENDING);
        this.status = TaskStatus.IN_PROGRESS;
        this.startedAt = LocalDateTime.now();
    }

    /** 수신 Agent가 시작 전 작업을 거절한다. 사유는 resultText, 종료 시각은 completedAt에 저장한다. */
    public void reject(Long agentId, String reason) {
        requireReceiverAndStatus(agentId, TaskStatus.PENDING);
        requireValue(reason, "rejectionReason");

        this.status = TaskStatus.REJECTED;
        this.resultText = reason;
        this.partiallyModified = false;
        this.completedAt = LocalDateTime.now();
    }

    /** 메서드 호출 시 작업 상태와 결과 파일 정보를 하나의 트랜잭션으로 저장
     *  이 메서드는 Task의 상태, 결과 요약, 완료 시각 변경을 담당하므로
     *  Service에서 TaskFile 정보를 저장할것
     * */
    public void complete(Long agentId, String resultText) {
        requireReceiverAndStatus(agentId, TaskStatus.IN_PROGRESS);
        requireValue(resultText, "resultText");

        this.status = TaskStatus.COMPLETED;
        this.resultText = resultText;
        this.completedAt = LocalDateTime.now();
    }

    public void fail(Long agentId, String reason, boolean partiallyModified) {
        requireReceiverAndStatus(agentId, TaskStatus.IN_PROGRESS);
        requireValue(reason, "reason");

        this.status = TaskStatus.FAILED;
        this.resultText = reason;
        this.partiallyModified = partiallyModified;
        this.completedAt = LocalDateTime.now();
    }

    private void requireReceiverAndStatus(Long agentId, TaskStatus expected) {
        if (!receiverAgentId.equals(agentId)) {
            throw new IllegalArgumentException("Only the receiver agent can change task status");
        }
        if (status != expected) {
            throw new IllegalStateException("Expected " + expected + " but task is " + status);
        }
    }

    @PrePersist
    private void initializeTimestamp() {
        this.createdAt = LocalDateTime.now();
    }

    private static void requireValue(Object value, String field) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new InvalidTaskInputException(field + " must not be null or blank");
        }
    }
}
