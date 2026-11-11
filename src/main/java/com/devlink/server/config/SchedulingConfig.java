package com.devlink.server.config;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;

@Configuration
public class SchedulingConfig {
    // TaskScheduler 타입으로 등록해 Boot의 applicationTaskExecutor 자동 구성을 유지한다.
    @Bean
    public TaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }

    @Bean
    public TaskScheduler teamContextScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.poolSize(1).threadNamePrefix("team-context-").build();
    }
}
