package com.devlink.server.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import com.devlink.server.config.OutboxPublisherSettings;
import com.devlink.server.config.TeamContextSettings;
import com.devlink.server.domain.outbox.OutboxEvent;
import com.devlink.server.domain.outbox.OutboxStatus;
import com.devlink.server.dto.OutboxDelivery;
import com.devlink.server.repository.OutboxEventRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * team-context destination의 점유·성공·실패 기록이다. 재시도 정책은 작업 알림 설정을 재사용한다.
 * 추론은 트랜잭션 밖에서 수행하고, 결과 저장과 성공 표시는 같은 트랜잭션에서 처리한다.
 */
@Service
public class TeamContextOutboxService {
    private final OutboxEventRepository outbox;
    private final OutboxPublisherSettings settings;
    private final TeamContextSettings contextSettings;
    private final Clock clock;

    public TeamContextOutboxService(OutboxEventRepository outbox, OutboxPublisherSettings settings,
            TeamContextSettings contextSettings, ObjectProvider<Clock> clocks) {
        this.outbox = outbox;
        this.settings = settings;
        this.contextSettings = contextSettings;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
    }

    public int batchSize() {
        return settings.batchSize();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<OutboxDelivery> claimNext() {
        Instant now = now();
        for (int index = 0; index < settings.batchSize(); index++) {
            var candidates = outbox.findNextTeamContextEvent(now);
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            var event = candidates.getFirst();
            if (event.getAttemptCount() >= settings.maxAttempts()) {
                event.publicationFailed("ATTEMPT_LIMIT_AFTER_LEASE_EXPIRY");
                outbox.flush();
                continue;
            }
            event.claim(UUID.randomUUID(), now, now.plusMillis(contextSettings.leaseMillis()));
            return Optional.of(OutboxDelivery.from(event));
        }
        return Optional.empty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean applied(UUID outboxId, UUID lockToken) {
        return applied(outboxId, lockToken, () -> { });
    }

    /** 점유 행을 잠근 상태로 요약을 저장해 다른 갱신기가 결과를 가로채지 못하게 한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean applied(UUID outboxId, UUID lockToken, Runnable saveSummary) {
        var owned = owned(outboxId, lockToken);
        if (owned.isEmpty()) {
            return false;
        }
        saveSummary.run();
        owned.get().published(now());
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failed(UUID outboxId, UUID lockToken, String reason, boolean permanent) {
        var owned = owned(outboxId, lockToken);
        if (owned.isEmpty()) {
            return false;
        }
        var event = owned.get();
        if (permanent || event.getAttemptCount() >= settings.maxAttempts()) {
            event.publicationFailed(reason);
        } else {
            event.retryAt(now().plusMillis(settings.retryDelayMillis(event.getAttemptCount())), reason);
        }
        return true;
    }

    private Optional<OutboxEvent> owned(UUID outboxId, UUID lockToken) {
        return outbox.findByOutboxIdAndLockTokenAndStatus(outboxId, lockToken, OutboxStatus.PROCESSING)
                .filter(event -> event.ownsLease(lockToken, now()));
    }

    private Instant now() {
        // PostgreSQL은 마이크로초로 반올림해 저장한다. Windows JVM 시계는 같은 값을 1ms 가까이 반복하므로
        // 버림(truncate)을 쓰면 방금 저장한 행의 available_at이 now보다 1µs 커서 점유를 놓친다. 같은 방식으로 반올림한다.
        return clock.instant().plusNanos(500).truncatedTo(ChronoUnit.MICROS);
    }
}
