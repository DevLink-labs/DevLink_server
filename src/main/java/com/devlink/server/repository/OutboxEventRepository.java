package com.devlink.server.repository;

import java.util.UUID;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import com.devlink.server.domain.outbox.OutboxEvent;
import com.devlink.server.domain.outbox.OutboxStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    // 짧은 점유 트랜잭션 안에서만 사용. 다른 발행기가 잡은 행은 기다리지 않고 건너뜀.
    // 질문·답변도 같은 발행기를 사용해 점유 만료 복구와 실패 재시도 정책을 공유
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE aggregate_type = 'TASK' AND destination = 'task-events'
              AND event_type IN ('TASK_STATUS_CHANGED', 'TASK_COMPLETED',
                                 'TASK_QUESTION_CREATED', 'TASK_ANSWER_CREATED')
              AND ((status = 'PENDING' AND available_at <= :now)
                OR (status = 'PROCESSING' AND locked_until <= :now))
            ORDER BY created_at, outbox_id
            LIMIT 1 FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findNextTaskNotification(@Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OutboxEvent> findByOutboxIdAndLockTokenAndStatus(UUID outboxId, UUID lockToken, OutboxStatus status);
}
