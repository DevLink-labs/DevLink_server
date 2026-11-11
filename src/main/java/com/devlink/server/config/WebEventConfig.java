package com.devlink.server.config;

import com.devlink.server.realtime.WebEventSessionListener;
import com.devlink.server.service.WebEventStreamService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebEventConfig {
    @Bean(name = "webEventExecutor", destroyMethod = "shutdown", defaultCandidate = false)
    ExecutorService webEventExecutor() {
        var sequence = new AtomicInteger();
        return new ThreadPoolExecutor(2, 8, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256), action -> {
            var thread = new Thread(action, "web-events-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    ServletListenerRegistrationBean<WebEventSessionListener> webEventSessionListener(WebEventStreamService streams) {
        return new ServletListenerRegistrationBean<>(new WebEventSessionListener(streams));
    }
}
