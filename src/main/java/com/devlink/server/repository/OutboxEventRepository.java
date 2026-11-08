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

    // 점유 불가 상태인 선행 행도 같은 쌍을 막는다. 파트 정보가 없는 V11 행은 팀 단위로 순서를 지킨다.
    @Query(value = """
            SELECT candidate.* FROM outbox_events candidate
            WHERE candidate.destination = 'team-context'
              AND ((candidate.status = 'PENDING' AND candidate.available_at <= :now)
                OR (candidate.status = 'PROCESSING' AND candidate.locked_until <= :now))
              AND NOT EXISTS (
                  SELECT 1 FROM outbox_events earlier
                  WHERE earlier.destination = 'team-context'
                    AND earlier.aggregate_id = candidate.aggregate_id
                    AND earlier.status IN ('PENDING', 'PROCESSING')
                    AND (earlier.context_order IS NULL OR candidate.context_order IS NULL
                         OR earlier.message_key = candidate.message_key)
                    AND (COALESCE(earlier.context_order, 0), earlier.created_at, earlier.outbox_id)
                      < (COALESCE(candidate.context_order, 0), candidate.created_at, candidate.outbox_id)
              )
            ORDER BY COALESCE(candidate.context_order, 0), candidate.created_at, candidate.outbox_id
            LIMIT 1 FOR UPDATE OF candidate SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findNextTeamContextEvent(@Param("now") Instant now);

    // 같은 쌍에 순번을 할당하는 트랜잭션은 커밋 순서도 직렬화한다.
    @Query(value = """
            SELECT nextval('team_context_outbox_order_seq')
            FROM pg_advisory_xact_lock(hashtextextended(:pairKey, 0))
            """, nativeQuery = true)
    long nextTeamContextOrder(@Param("pairKey") String pairKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OutboxEvent> findByOutboxIdAndLockTokenAndStatus(UUID outboxId, UUID lockToken, OutboxStatus status);
}
