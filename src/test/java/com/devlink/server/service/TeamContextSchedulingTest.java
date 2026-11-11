package com.devlink.server.service;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.devlink.server.client.TeamContextInferenceClient;
import com.devlink.server.config.SchedulingConfig;
import com.devlink.server.config.TeamContextSettings;
import com.devlink.server.dto.OutboxDelivery;
import com.devlink.server.dto.teamcontext.TeamContextInferenceRequest;
import com.devlink.server.dto.teamcontext.TeamContextSummary;
import org.junit.jupiter.api.Test;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TeamContextSchedulingTest {
    @Test
    void defaultScheduledWorkRunsWhileContextInferenceIsWaiting() throws InterruptedException {
        var inferenceStarted = new CountDownLatch(1);
        var inferenceMayFinish = new CountDownLatch(1);
        var defaultTick = new CountDownLatch(1);
        var settings = new TeamContextSettings(true, "", "", true, "", 60_000, 6_000, 20, 2_000, "v1", false);
        var outbox = mock(TeamContextOutboxService.class);
        var store = mock(TeamContextStore.class);
        var inference = mock(TeamContextInferenceClient.class);
        var delivery = mock(OutboxDelivery.class);
        var prepared = mock(TeamContextStore.Prepared.class);
        var request = mock(TeamContextInferenceRequest.class);
        when(outbox.batchSize()).thenReturn(1);
        when(outbox.claimNext()).thenReturn(Optional.of(delivery)).thenReturn(Optional.empty());
        when(store.prepare(delivery)).thenReturn(Optional.of(prepared));
        when(prepared.request()).thenReturn(request);
        when(inference.infer(request)).thenAnswer(invocation -> {
            inferenceStarted.countDown();
            inferenceMayFinish.await(15, TimeUnit.SECONDS);
            return TeamContextSummary.empty();
        });

        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(SchedulingEnabled.class, SchedulingConfig.class);
            context.registerBean(ThreadPoolTaskSchedulerBuilder.class, ThreadPoolTaskSchedulerBuilder::new);
            context.registerBean(TeamContextUpdater.class, () -> new TeamContextUpdater(outbox, store, inference, settings));
            context.registerBean(DefaultJob.class, () -> new DefaultJob(inferenceStarted, defaultTick));
            try {
                context.refresh();
                assertThat(inferenceStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(defaultTick.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                inferenceMayFinish.countDown();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingEnabled { }

    record DefaultJob(CountDownLatch inferenceStarted, CountDownLatch defaultTick) {
        @Scheduled(fixedDelay = 10)
        public void tick() {
            if (inferenceStarted.getCount() == 0) {
                defaultTick.countDown();
            }
        }
    }
}
