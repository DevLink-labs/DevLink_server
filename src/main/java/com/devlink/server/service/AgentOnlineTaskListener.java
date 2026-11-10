package com.devlink.server.service;

import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.service.AgentPresenceService.AgentOnline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class AgentOnlineTaskListener {
    private static final Logger log = LoggerFactory.getLogger(AgentOnlineTaskListener.class);
    private final ConnectionRegistry connections;
    private final TaskCreationService creation;
    private final TaskExecutor executor;

    AgentOnlineTaskListener(ConnectionRegistry connections, TaskCreationService creation,
            @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.connections = connections;
        this.creation = creation;
        this.executor = executor;
    }

    @TransactionalEventListener
    public void retryTasks(AgentOnline event) {
        // 재전송 중에도 WebSocket이 ACK와 하트비트 메시지를 처리할 수 있게 한다.
        executor.execute(() -> {
            try {
                if (connections.isCurrentConnection(Long.toString(event.agentId()), event.session())) {
                    creation.retryUndelivered(event.agentId(), event.session());
                }
            } catch (RuntimeException exception) {
                log.error("Failed to retry undelivered tasks for agent {}", event.agentId(), exception);
            }
        });
    }
}
