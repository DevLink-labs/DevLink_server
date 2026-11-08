package com.devlink.server.service;

import com.devlink.server.client.TeamContextInferenceClient;
import com.devlink.server.config.TeamContextSettings;
import com.devlink.server.dto.OutboxDelivery;
import com.devlink.server.dto.teamcontext.TeamContextSummary;
import com.devlink.server.exception.InvalidTeamContextEventException;
import com.devlink.server.exception.TeamContextInferenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * team-context Outbox를 소비해 파트 쌍 요약을 증분 갱신한다. 작업·질문·답변 쓰기 트랜잭션 밖에서 돌며,
 * 추론 실패는 재시도하고 가리키는 데이터가 없는 메시지는 영구 실패로 기록한다.
 */
@Component
public class TeamContextUpdater {
    private static final Logger log = LoggerFactory.getLogger(TeamContextUpdater.class);
    private final TeamContextOutboxService outbox;
    private final TeamContextStore store;
    private final TeamContextInferenceClient inference;
    private final TeamContextSettings settings;

    public TeamContextUpdater(TeamContextOutboxService outbox, TeamContextStore store,
            TeamContextInferenceClient inference, TeamContextSettings settings) {
        this.outbox = outbox;
        this.store = store;
        this.inference = inference;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${devlink.team-context.poll-interval-ms:1000}", scheduler = "teamContextScheduler")
    public void poll() {
        if (settings.enabled()) {
            processBatch();
        }
    }

    @Transactional(propagation = Propagation.NEVER)
    public int processBatch() {
        int handled = 0;
        for (int index = 0; index < outbox.batchSize(); index++) {
            OutboxDelivery delivery;
            try {
                var next = outbox.claimNext();
                if (next.isEmpty()) {
                    break;
                }
                delivery = next.get();
            } catch (RuntimeException exception) {
                log.error("Could not claim team-context outbox", exception);
                break;
            }
            process(delivery);
            handled++;
        }
        return handled;
    }

    private void process(OutboxDelivery delivery) {
        try {
            var prepared = store.prepare(delivery);
            if (prepared.isEmpty()) {
                outbox.applied(delivery.outboxId(), delivery.lockToken());
                return;
            }
            TeamContextSummary summary = inference.infer(prepared.get().request());
            if (!outbox.applied(delivery.outboxId(), delivery.lockToken(),
                    () -> store.save(prepared.get(), summary, inference.model()))) {
                log.info("Team-context outbox {} no longer owns its lease; another updater will retry",
                        delivery.outboxId());
            }
        } catch (InvalidTeamContextEventException exception) {
            log.warn("Team-context outbox {} is invalid: {}", delivery.outboxId(), exception.getMessage());
            recordFailure(delivery, "INVALID_EVENT", true);
        } catch (TeamContextInferenceException exception) {
            log.warn("Team-context inference failed for {}: {}", delivery.outboxId(), exception.getMessage());
            recordFailure(delivery, "INFERENCE_FAILED", false);
        } catch (OptimisticLockingFailureException exception) {
            log.info("Team-context changed during inference for {}; retrying", delivery.outboxId());
            recordFailure(delivery, "STALE_CONTEXT", false);
        } catch (RuntimeException exception) {
            log.error("Team-context update failed for {}", delivery.outboxId(), exception);
            recordFailure(delivery, "INTERNAL_ERROR", false);
        }
    }

    private void recordFailure(OutboxDelivery delivery, String reason, boolean permanent) {
        try {
            outbox.failed(delivery.outboxId(), delivery.lockToken(), reason, permanent);
        } catch (RuntimeException exception) {
            log.error("Could not record team-context failure {}", delivery.outboxId(), exception);
        }
    }
}
