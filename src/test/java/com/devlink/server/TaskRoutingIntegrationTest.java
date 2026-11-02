package com.devlink.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.AgentStatus;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.user.User;
import com.devlink.server.dto.TaskRoutingResult.Status;
import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.realtime.TaskCreatedEvent;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TeamRepository;
import com.devlink.server.repository.UserRepository;
import com.devlink.server.service.AgentTokenVerifier;
import com.devlink.server.service.TaskRoutingService;
import com.devlink.server.service.TaskCreationService;
import com.devlink.server.dto.CreateTaskRequest;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.repository.TaskEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** 인증·Rust 브로커 대신 테스트 전용 신원과 Java 클라이언트로 실제 소켓·DB를 검증한다. */
@Import(TaskRoutingIntegrationTest.TokenVerificationFixture.class)
class TaskRoutingIntegrationTest extends AbstractIntegrationTest {
    @Autowired private TaskRoutingService routing;
    @Autowired private TaskCreationService creation;
    @Autowired private TaskEventRepository taskEvents;
    @Autowired private TaskRepository tasks;
    @Autowired private AgentRepository agents;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private ConnectionRegistry connections;
    @Autowired private ObjectMapper mapper;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private AtomicReference<Map<String, AgentPrincipal>> verifiedAgents;
    @LocalServerPort private int port;
    private Long teamId;
    private Agent requester;
    private Agent receiver;

    @BeforeEach
    void createAgents() {
        String unique = UUID.randomUUID().toString();
        Team team = teams.save(Team.create("Routing", unique));
        teamId = team.getId();
        requester = createAgent("requester-" + unique);
        receiver = createAgent("receiver-" + unique);
        verifiedAgents.set(Map.of("requester-token", principal(requester), "receiver-token", principal(receiver)));
    }

    @Test
    void creatingTaskRoutesToRealBrokerOnceAfterCommit() throws Exception {
        try (var client = HttpClient.newHttpClient();
                var broker = connect(client, "receiver-token", receiver.getId())) {
            broker.presence("ONLINE");
            var request = new CreateTaskRequest(UUID.randomUUID().toString(),
                    receiver.getId(), "Create and route");
            var result = creation.create(teamId, requester.getId(), request);
            assertThat(broker.next().path("payload").path("task_id").asLong()).isEqualTo(result.taskId());
            assertThat(result.delivery().status()).isEqualTo(TaskEventType.ACK_PENDING);
            assertThat(tasks.findById(result.taskId()).orElseThrow().getDeliveredAt()).isNull();
            assertThat(taskEvents.findByTaskIdOrderByCreatedAtAsc(result.taskId()))
                    .extracting(TaskEvent::getEventType).containsExactly(TaskEventType.CREATED, TaskEventType.ACK_PENDING);
            var replay = creation.create(teamId, requester.getId(), request);
            assertThat(replay.replayed()).isTrue();
            assertThat(broker.listener.messages.poll(200, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    void readyOnlyAndStaleOnlineReceiversCannotCreateAnyTask() throws Exception {
        String key = UUID.randomUUID().toString();
        var request = new CreateTaskRequest(key, receiver.getId(), "Offline preflight");
        try (var client = HttpClient.newHttpClient();
                var broker = connect(client, "receiver-token", receiver.getId())) {
            assertOfflineCreationRejected(request);
            assertThat(broker.listener.messages.poll(200, TimeUnit.MILLISECONDS)).isNull();
        }
        Agent stale = agents.findById(receiver.getId()).orElseThrow();
        stale.markOnline(LocalDateTime.now());
        agents.saveAndFlush(stale);
        assertOfflineCreationRejected(request);
    }

    private void assertOfflineCreationRejected(CreateTaskRequest request) {
        long eventsBefore = taskEvents.count();
        assertThatThrownBy(() -> creation.create(teamId, requester.getId(), request))
                .isInstanceOfSatisfying(com.devlink.server.common.BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(com.devlink.server.common.ErrorCode.RECEIVER_OFFLINE));
        assertThat(tasks.findByTeamIdOrderByCreatedAtDesc(teamId)).isEmpty();
        assertThat(taskEvents.count()).isEqualTo(eventsBefore);
    }

    @Test
    void onlyDesignatedReceiverGetsTaskAndSocketWriteDoesNotRecordDelivery() throws Exception {
        try (var client = HttpClient.newHttpClient();
                var requesterBroker = connect(client, "requester-token", requester.getId());
                var receiverBroker = connect(client, "receiver-token", receiver.getId())) {
            requesterBroker.presence("ONLINE");
            receiverBroker.presence("ONLINE");
            Task task = saveTask();

            var result = routing.sendTask(task.getId());

            assertThat(result.status()).isEqualTo(Status.SENT);
            JsonNode event = receiverBroker.next();
            assertThat(event.path("type").asText()).isEqualTo("TASK_CREATED");
            assertThat(event.path("event_id").asText()).isEqualTo(result.eventId());
            assertThat(event.path("payload").path("task_id").asLong()).isEqualTo(task.getId());
            assertThat(event.path("payload").path("receiver_agent_id").asLong()).isEqualTo(receiver.getId());
            assertThat(event.path("payload").path("prompt").asText()).isEqualTo(task.getPrompt());
            assertThat(requesterBroker.listener.messages.poll(200, TimeUnit.MILLISECONDS)).isNull();
            assertPendingWithoutDelivery(task.getId());

            // 작업 전송에 공통 전송기를 붙인 뒤에도 기존 JSON 하트비트가 정상 동작해야 한다.
            receiverBroker.socket.sendText(
                    "{\"type\":\"HEARTBEAT\",\"payload\":{\"kind\":\"PING\",\"ping_id\":\"after-task\"}}", true)
                    .get(5, TimeUnit.SECONDS);
            assertThat(receiverBroker.next().path("payload").path("kind").asText()).isEqualTo("PONG");
        }
    }

    @Test
    void routesAfterCommitButNeverBeforeCommitOrAfterRollback() throws Exception {
        try (var client = HttpClient.newHttpClient();
                var broker = connect(client, "receiver-token", receiver.getId())) {
            broker.presence("ONLINE");
            var transaction = new TransactionTemplate(transactionManager);
            Long committedId = transaction.execute(status -> {
                Task task = saveTask();
                events.publishEvent(new TaskCreatedEvent(task.getId()));
                // 아직 커밋되지 않았으므로 소켓 전송은 시작되지 않아야 한다.
                assertThat(broker.listener.messages).isEmpty();
                return task.getId();
            });
            assertThat(broker.next().path("payload").path("task_id").asLong()).isEqualTo(committedId);
            assertPendingWithoutDelivery(committedId);

            Long rolledBackId = transaction.execute(status -> {
                Task task = saveTask();
                events.publishEvent(new TaskCreatedEvent(task.getId()));
                status.setRollbackOnly();
                return task.getId();
            });
            assertThat(tasks.findById(rolledBackId)).isEmpty();
            assertThat(broker.listener.messages.poll(200, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    void refusesDirectRoutingInsideTransaction() throws Exception {
        try (var client = HttpClient.newHttpClient();
                var broker = connect(client, "receiver-token", receiver.getId())) {
            broker.presence("ONLINE");
            Task task = saveTask();
            var transaction = new TransactionTemplate(transactionManager);

            assertThatThrownBy(() -> transaction.execute(status -> routing.sendTask(task.getId())))
                    .isInstanceOf(IllegalTransactionStateException.class);

            assertThat(broker.listener.messages.poll(200, TimeUnit.MILLISECONDS)).isNull();
            assertPendingWithoutDelivery(task.getId());
        }
    }

    @Test
    void offlineAgentAndStaleOnlineStateWithoutConnectionPreserveStoredTask() throws Exception {
        Task task = saveTask();
        try (var client = HttpClient.newHttpClient();
                var broker = connect(client, "receiver-token", receiver.getId())) {
            // READY만 받은 상태는 ONLINE이 아니다. 열린 브로커가 있어도 작업을 보내지 않는다.
            assertThat(routing.sendTask(task.getId()).status()).isEqualTo(Status.RECEIVER_OFFLINE);
            assertThat(broker.listener.messages.poll(200, TimeUnit.MILLISECONDS)).isNull();
        }
        Agent stale = agents.findById(receiver.getId()).orElseThrow();
        stale.markOnline(LocalDateTime.now());
        agents.save(stale);

        assertThat(routing.sendTask(task.getId()).status()).isEqualTo(Status.CONNECTION_UNAVAILABLE);
        assertPendingWithoutDelivery(task.getId());
    }

    private Agent createAgent(String name) {
        User user = users.save(User.create(name + "@example.com", "Routing", null, "test-hash"));
        return agents.save(Agent.create(teamId, user.getId(), name, null, ToolType.CODEX, "server"));
    }

    private AgentPrincipal principal(Agent agent) {
        return new AgentPrincipal(agent.getId(), agent.getOwnerUserId(), agent.getTeamId());
    }

    private Task saveTask() {
        return tasks.save(Task.create(UUID.randomUUID().toString(), teamId, requester.getId(),
                requester.getOwnerUserId(), receiver.getId(), "수신자에게만 전달할 작업"));
    }

    private void assertPendingWithoutDelivery(long taskId) {
        Task stored = tasks.findById(taskId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(stored.getDeliveredAt()).isNull();
        assertThat(stored.getVersion()).isZero();
    }

    private TestBroker connect(HttpClient client, String token, long agentId) throws Exception {
        var listener = new BrokerListener();
        var socket = client.newWebSocketBuilder()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/broker"), listener)
                .get(5, TimeUnit.SECONDS);
        var broker = new TestBroker(agentId, socket, listener);
        assertThat(broker.next().path("type").asText()).isEqualTo("BROKER_READY");
        return broker;
    }

    private final class TestBroker implements AutoCloseable {
        private final long agentId;
        private final WebSocket socket;
        private final BrokerListener listener;

        private TestBroker(long agentId, WebSocket socket, BrokerListener listener) {
            this.agentId = agentId;
            this.socket = socket;
            this.listener = listener;
        }

        private JsonNode next() throws Exception {
            String message = listener.messages.poll(5, TimeUnit.SECONDS);
            assertThat(message).as("서버 JSON 응답").isNotNull();
            return mapper.readTree(message);
        }

        private void presence(String status) throws Exception {
            socket.sendText("{\"type\":\"AGENT_PRESENCE\",\"payload\":{\"status\":\"" + status + "\"}}", true)
                    .get(5, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(agents.findById(agentId).orElseThrow().getStatus().name()).isEqualTo(status));
        }

        @Override
        public void close() {
            socket.abort();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(connections.findByAgentId(Long.toString(agentId))).isEmpty();
                assertThat(agents.findById(agentId).orElseThrow().getStatus()).isEqualTo(AgentStatus.OFFLINE);
            });
        }
    }

    private static class BrokerListener implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder text = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                messages.add(text.toString());
                text.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TokenVerificationFixture {
        @Bean
        AtomicReference<Map<String, AgentPrincipal>> verifiedAgents() {
            return new AtomicReference<>(Map.of());
        }

        @Bean
        AgentTokenVerifier agentTokenVerifier(AtomicReference<Map<String, AgentPrincipal>> verifiedAgents) {
            return token -> Optional.ofNullable(verifiedAgents.get().get(token));
        }
    }
}
