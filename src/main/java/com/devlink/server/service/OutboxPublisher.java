package com.devlink.server.service;

import com.devlink.server.config.OutboxPublisherSettings;
import com.devlink.server.dto.OutboxDelivery;
import com.devlink.server.dto.TaskNotificationResult.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 상태·완료 알림은 요청자에게, 질문·답변 알림은 저장된 상대 Agent에게 발행 */
@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxPublicationService publications;
    private final TaskNotificationService notifications;
    private final OutboxPublisherSettings settings;

    public OutboxPublisher(OutboxPublicationService publications, TaskNotificationService notifications,
            OutboxPublisherSettings settings) {
        this.publications = publications;
        this.notifications = notifications;
        this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${devlink.outbox.poll-interval-ms:1000}")
    public void poll() {
        if (settings.enabled()) {
            publishBatch();
        }
    }

    @Transactional(propagation = Propagation.NEVER)
    public int publishBatch() {
        int handled = 0;
        for (int index = 0; index < settings.batchSize(); index++) {
            OutboxDelivery delivery;
            try {
                var next = publications.claimNext();
                if (next.isEmpty()) {
                    break;
                }
                delivery = next.get();
            } catch (RuntimeException exception) {
                log.error("Could not claim task notification outbox", exception);
                break;
            }
            publish(delivery);
            handled++;
        }
        return handled;
    }

    private void publish(OutboxDelivery delivery) {
        try {
            // 점유 커밋 후 전송한다. 소켓 대기 중 점유가 만료·교체됐다면 실제 쓰기 전에 중단한다.
            var result = notifications.sendNotification(delivery, () -> {
                if (!publications.renewBeforeSend(delivery.outboxId(), delivery.lockToken())) {
                    throw new LeaseLostException();
                }
            });
            if (result.status() == Status.SENT) {
                // 옵션 1: 브로커 ACK가 아닌 소켓 쓰기 성공을 PUBLISHED로 기록한다.
                publications.published(delivery.outboxId(), delivery.lockToken());
            } else {
                recordFailure(delivery, result.status().name(), result.status() == Status.INVALID_EVENT);
            }
        } catch (LeaseLostException exception) {
            log.info("Outbox {} no longer owns its publication lease", delivery.outboxId());
        } catch (RuntimeException exception) {
            log.error("Outbox publication failed for {}", delivery.outboxId(), exception);
            recordFailure(delivery, "INTERNAL_ERROR", false);
        }
    }

    private void recordFailure(OutboxDelivery delivery, String reason, boolean permanent) {
        try {
            publications.failed(delivery.outboxId(), delivery.lockToken(), reason, permanent);
        } catch (RuntimeException exception) {
            // 기록도 실패하면 PROCESSING이 남고, 점유 만료 후 다른 발행기가 복구한다.
            log.error("Could not record outbox failure {}", delivery.outboxId(), exception);
        }
    }

    private static final class LeaseLostException extends RuntimeException { }
}
