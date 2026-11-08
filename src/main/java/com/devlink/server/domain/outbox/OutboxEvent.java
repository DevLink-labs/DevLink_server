package com.devlink.server.domain.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Persist in the same transaction as the business change. Publication is asynchronous. */
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {
    @Id
    @Column(name = "outbox_id", nullable = false, updatable = false)
    private UUID outboxId;

    @Column(name = "event_type", length = 100, nullable = false, updatable = false)
    private String eventType;

    @Column(name = "aggregate_type", length = 50, nullable = false, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", length = 100, nullable = false, updatable = false)
    private String aggregateId;

    @Column(name = "destination", length = 255, nullable = false, updatable = false)
    private String destination;

    @Column(name = "message_key", length = 255, updatable = false)
    private String messageKey;

    @Column(name = "context_order", updatable = false)
    private Long contextOrder;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "message", nullable = false, updatable = false, columnDefinition = "JSONB")
    private JsonNode message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private OutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "lock_token")
    private UUID lockToken;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /** message.event_id matches outboxId; the wire envelope retains its event_id field. */
    public static OutboxEvent create(UUID outboxId, String eventType, String aggregateType,
                                     String aggregateId, String destination, String messageKey,
                                     JsonNode message) {
        return create(outboxId, eventType, aggregateType, aggregateId, destination, messageKey, message, null);
    }

    public static OutboxEvent create(UUID outboxId, String eventType, String aggregateType,
                                     String aggregateId, String destination, String messageKey,
                                     JsonNode message, Long contextOrder) {
        Objects.requireNonNull(outboxId, "outboxId must not be null");
        requireText(eventType, "eventType", 100);
        requireText(aggregateType, "aggregateType", 50);
        requireText(aggregateId, "aggregateId", 100);
        requireText(destination, "destination", 255);
        if (messageKey != null) {
            requireText(messageKey, "messageKey", 255);
        }
        if (message == null || !message.isObject()
                || !outboxId.toString().equals(message.path("event_id").asText())
                || !eventType.equals(message.path("type").asText())) {
            throw new IllegalArgumentException("message must be an object with matching event_id and type");
        }
        OutboxEvent entity = new OutboxEvent();
        entity.outboxId = outboxId;
        entity.eventType = eventType;
        entity.aggregateType = aggregateType;
        entity.aggregateId = aggregateId;
        entity.destination = destination;
        entity.messageKey = messageKey;
        entity.contextOrder = contextOrder;
        entity.message = message.deepCopy();
        entity.status = OutboxStatus.PENDING;
        return entity;
    }

    public JsonNode getMessage() {
        return message.deepCopy();
    }

    /** 행 잠금을 획득한 발행기가 점유한다. 메시지와 식별자는 재시도 중에도 바꾸지 않는다. */
    public void claim(UUID token, Instant now, Instant leaseEnd) {
        boolean ready = status == OutboxStatus.PENDING && !availableAt.isAfter(now);
        boolean abandoned = status == OutboxStatus.PROCESSING && lockedUntil != null && !lockedUntil.isAfter(now);
        if ((!ready && !abandoned) || token == null || !leaseEnd.isAfter(now)) {
            throw new IllegalStateException("Outbox event is not claimable");
        }
        status = OutboxStatus.PROCESSING;
        lockToken = token;
        lockedUntil = leaseEnd;
        attemptCount++;
    }

    public boolean ownsLease(UUID token, Instant now) {
        return status == OutboxStatus.PROCESSING && Objects.equals(lockToken, token)
                && lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** 소켓 쓰기 차례를 기다린 뒤 실제 전송 직전에 점유를 연장한다. */
    public void renewLease(Instant leaseEnd) {
        lockedUntil = leaseEnd;
    }

    /** PUBLISHED는 소켓 쓰기 성공이며 브로커 수신 ACK나 작업 완료를 뜻하지 않는다. */
    public void published(Instant now) {
        status = OutboxStatus.PUBLISHED;
        publishedAt = now;
        lastError = null;
        clearLease();
    }

    public void retryAt(Instant nextAttempt, String error) {
        status = OutboxStatus.PENDING;
        availableAt = nextAttempt;
        lastError = error;
        clearLease();
    }

    public void publicationFailed(String error) {
        status = OutboxStatus.FAILED;
        lastError = error;
        clearLease();
    }

    private void clearLease() {
        lockToken = null;
        lockedUntil = null;
    }

    @PrePersist
    private void initializeTimestamps() {
        this.createdAt = Instant.now();
        this.availableAt = this.createdAt;
    }

    private static void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not be blank or exceed " + maxLength + " characters");
        }
    }
}
