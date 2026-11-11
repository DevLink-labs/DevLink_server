package com.devlink.server;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import com.devlink.server.domain.outbox.OutboxEvent;
import com.devlink.server.domain.outbox.OutboxStatus;
import com.devlink.server.repository.OutboxEventRepository;
import com.devlink.server.service.TeamContextOutboxService;
import com.devlink.server.service.TeamContextOutboxWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 저장 직후 점유가 비지 않는지 반복 확인한다. PostgreSQL의 마이크로초 반올림과 JVM 시계의 버림이 어긋나면
 * 같은 시계 틱에 저장된 행을 놓치므로, 점유 시각은 같은 방식으로 반올림해야 한다.
 */
class TeamContextOutboxServiceIntegrationTest extends AbstractIntegrationTest {
    @Autowired OutboxEventRepository outbox;
    @Autowired TeamContextOutboxService service;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean Clock clock;
    private final AtomicLong clockOffset = new AtomicLong();

    @BeforeEach
    void setClock() {
        when(clock.instant()).thenAnswer(invocation -> Instant.now().plusSeconds(clockOffset.get()));
    }

    @Test
    void claimsRowsImmediatelyAfterTheyAreCommitted() {
        var tx = new TransactionTemplate(transactionManager);
        List<String> misses = new ArrayList<>();
        for (int index = 0; index < 80; index++) {
            UUID id = UUID.randomUUID();
            var envelope = mapper.createObjectNode().put("event_id", id.toString())
                    .put("type", TeamContextOutboxWriter.TYPE).put("team_id", 1).put("task_id", 1);
            envelope.putObject("payload").put("task_event_id", 1).put("event_type", "CREATED");
            tx.executeWithoutResult(status -> outbox.save(OutboxEvent.create(id, TeamContextOutboxWriter.TYPE,
                    TeamContextOutboxWriter.AGGREGATE_TYPE, "1", TeamContextOutboxWriter.DESTINATION, null, envelope)));
            var claimed = service.claimNext();
            if (claimed.isEmpty()) {
                misses.add(index + ": " + outbox.findById(id).orElseThrow().getAvailableAt());
                continue;
            }
            assertThat(claimed.get().outboxId()).isEqualTo(id);
            assertThat(service.applied(id, claimed.get().lockToken())).isTrue();
            assertThat(outbox.findById(id).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        }
        assertThat(misses).as("rows not claimable right after commit").isEmpty();
        assertThat(service.claimNext()).isEmpty();
    }

    @Test
    void failedRowsRetryUntilTheAttemptLimitUnlessPermanent() {
        String pair = "TEAM_CONTEXT:1:BACKEND:FRONTEND";
        UUID retry = enqueue(pair);
        UUID later = enqueue(pair);
        UUID permanent = enqueue("TEAM_CONTEXT:1:BACKEND:INFRA");
        var first = service.claimNext().orElseThrow();
        assertThat(first.outboxId()).isEqualTo(retry);
        // 두 번째 워커도 같은 쌍의 후속 이벤트를 앞질러 점유할 수 없다.
        var second = service.claimNext().orElseThrow();
        assertThat(second.outboxId()).isEqualTo(permanent);
        assertThat(service.failed(permanent, second.lockToken(), "INVALID_EVENT", true)).isTrue();
        assertThat(outbox.findById(permanent).orElseThrow().getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(service.failed(retry, first.lockToken(), "INFERENCE_FAILED", false)).isTrue();
        var retried = outbox.findById(retry).orElseThrow();
        assertThat(retried.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(retried.getAttemptCount()).isEqualTo(1);
        assertThat(retried.getLastError()).isEqualTo("INFERENCE_FAILED");
        assertThat(retried.getAvailableAt()).isAfter(retried.getCreatedAt());

        // 재시도 지연 중에도 후속 행은 점유할 수 없다.
        assertThat(service.claimNext()).isEmpty();
        assertThat(service.applied(permanent, second.lockToken())).isFalse();
        clockOffset.set(2);
        var retriedClaim = service.claimNext().orElseThrow();
        assertThat(retriedClaim.outboxId()).isEqualTo(retry);
        assertThat(service.applied(retry, retriedClaim.lockToken())).isTrue();
        var laterClaim = service.claimNext().orElseThrow();
        assertThat(laterClaim.outboxId()).isEqualTo(later);
        assertThat(service.applied(later, laterClaim.lockToken())).isTrue();
    }

    @Test
    void acceptsAnInferenceThatFinishesAfterTheTaskNotificationLease() {
        UUID id = enqueue("TEAM_CONTEXT:1:BACKEND:FRONTEND");
        var claimed = service.claimNext().orElseThrow();
        assertThat(claimed.outboxId()).isEqualTo(id);
        clockOffset.set(35);
        assertThat(service.applied(id, claimed.lockToken())).isTrue();
        assertThat(outbox.findById(id).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    private UUID enqueue(String pairKey) {
        UUID id = UUID.randomUUID();
        var envelope = mapper.createObjectNode().put("event_id", id.toString())
                .put("type", TeamContextOutboxWriter.TYPE).put("team_id", 1).put("task_id", 1);
        envelope.putObject("payload").put("task_event_id", 1).put("event_type", "CREATED");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                outbox.save(OutboxEvent.create(id, TeamContextOutboxWriter.TYPE,
                        TeamContextOutboxWriter.AGGREGATE_TYPE, "1", TeamContextOutboxWriter.DESTINATION,
                        pairKey, envelope, outbox.nextTeamContextOrder(pairKey))));
        return id;
    }
}
