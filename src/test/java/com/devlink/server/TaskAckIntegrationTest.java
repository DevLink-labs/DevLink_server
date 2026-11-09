package com.devlink.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
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
import com.devlink.server.dto.TaskAckResult;
import com.devlink.server.dto.TaskAckResult.Status;
import com.devlink.server.dto.TaskRoutingResult;
import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TeamRepository;
import com.devlink.server.repository.UserRepository;
import com.devlink.server.service.AgentTokenVerifier;
import com.devlink.server.service.TaskRoutingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// 실제 WebSocket·DB·스케줄러를 사용하고, 이 테스트의 ACK 제한 시간만 2초로 줄인다.
@TestPropertySource(properties = {
        "devlink.broker.ack-timeout-ms=2000",
        "devlink.broker.ack-check-interval-ms=25"
})
@Import(TaskAckIntegrationTest.Fixture.class)
class TaskAckIntegrationTest extends AbstractIntegrationTest {
    @Autowired private TaskRoutingService routing;
    @Autowired private TaskRepository tasks;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.devlink.server.repository.TaskEventRepository history;
    @Autowired private AgentRepository agents;
    @Autowired private TeamRepository teams;
    @Autowired private UserRepository users;
    @Autowired private ConnectionRegistry connections;
    @Autowired private ObjectMapper mapper;
    @Autowired private AtomicReference<Map<String, AgentPrincipal>> verifiedAgents;
    @Autowired private AckResults ackResults;
    @LocalServerPort private int port;
    private Long teamId;
    private Agent requester;
    private Agent receiver;

    @BeforeEach
    void setup() {
        ackResults.results.clear();
        String unique = UUID.randomUUID().toString();
        teamId = teams.save(Team.create("ACK", unique)).getId();
        requester = createAgent("requester-" + unique);
        receiver = createAgent("receiver-" + unique);
        verifiedAgents.set(Map.of("requester", principal(requester), "receiver", principal(receiver)));
    }

    @Test
    void validAckCompletesOnceAndDuplicateOrUnknownAckKeepsConnectionOpen() throws Exception {
        try (var client = HttpClient.newHttpClient(); var broker = connect(client, "receiver", receiver)) {
            broker.online();
            Task task = saveTask();
            var sent = routing.sendTask(task.getId());
            assertThat(sent.status()).isEqualTo(TaskRoutingResult.Status.SENT);
            assertThat(broker.next().path("event_id").asText()).isEqualTo(sent.eventId());

            broker.ack(task.getId(), sent.eventId());

            assertThat(nextResult()).isEqualTo(new TaskAckResult(
                    task.getId(), sent.eventId(), sent.connectionId(), Status.ACKNOWLEDGED));
            broker.ack(task.getId(), sent.eventId());
            broker.ack(task.getId(), "unknown");
            broker.barrier();
            assertThat(ackResults.results).isEmpty();
            assertThat(connections.findByAgentId(receiver.getId().toString())).isPresent();
            assertAcknowledgedTask(task.getId());
        }
    }

    @Test
    void anotherAgentAndWrongTaskIdCannotFinishReceiverWait() throws Exception {
        try (var client = HttpClient.newHttpClient();
                var other = connect(client, "requester", requester);
                var broker = connect(client, "receiver", receiver)) {
            broker.online();
            Task task = saveTask();
            var sent = routing.sendTask(task.getId());
            broker.next();

            other.ack(task.getId(), sent.eventId());
            other.barrier();
            broker.ack(task.getId() + 1, sent.eventId());
            broker.barrier();
            assertThat(ackResults.results).isEmpty();

            broker.ack(task.getId(), sent.eventId());
            assertThat(nextResult().status()).isEqualTo(Status.ACKNOWLEDGED);
            assertAcknowledgedTask(task.getId());
        }
    }

    @Test
    void missingAckTimesOutAndLateAckCannotChangeTimeoutResult() throws Exception {
        try (var client = HttpClient.newHttpClient(); var broker = connect(client, "receiver", receiver)) {
            broker.online();
            Task task = saveTask();
            var sent = routing.sendTask(task.getId());
            broker.next();

            var result = nextResult();
            assertThat(result.eventId()).isEqualTo(sent.eventId());
            assertThat(result.status()).isEqualTo(Status.TIMED_OUT);
            broker.ack(task.getId(), sent.eventId());
            broker.barrier();
            assertThat(ackResults.results).isEmpty();
            assertUnchangedTask(task.getId());
        }
    }

    @Test
    void reconnectEndsOldWaitAndOnlyNewEventCanAcknowledgeRetriedTask() throws Exception {
        try (var client = HttpClient.newHttpClient(); var previous = connect(client, "receiver", receiver)) {
            previous.online();
            Task oldTask = saveTask();
            var oldSend = routing.sendTask(oldTask.getId());
            previous.next();
            try (var current = connect(client, "receiver", receiver)) {
                var closed = nextResult();
                assertThat(closed.eventId()).isEqualTo(oldSend.eventId());
                assertThat(closed.status()).isEqualTo(Status.CONNECTION_CLOSED);
                current.online();
                var retried = current.next();
                assertThat(retried.path("payload").path("task_id").asLong()).isEqualTo(oldTask.getId());
                String newEventId = retried.path("event_id").asText();
                assertThat(newEventId).isNotEqualTo(oldSend.eventId());

                // 같은 작업이어도 새 연결에서는 이전 전송의 ACK를 받아들이지 않는다.
                current.ack(oldTask.getId(), oldSend.eventId());
                current.barrier();
                assertThat(ackResults.results).isEmpty();

                current.ack(oldTask.getId(), newEventId);
                var received = nextResult();
                assertThat(received.eventId()).isEqualTo(newEventId);
                assertThat(received.status()).isEqualTo(Status.ACKNOWLEDGED);
                assertThat(tasks.findById(oldTask.getId()).orElseThrow().getDeliveredAt()).isNotNull();
            }
        }
    }

    private Agent createAgent(String name) {
        User user = users.save(User.create(name + "@ack.test", "ACK", null, "test-hash"));
        return agents.save(Agent.create(teamId, user.getId(), name, null, ToolType.CODEX, "server"));
    }

    private AgentPrincipal principal(Agent agent) {
        return new AgentPrincipal(agent.getId(), agent.getOwnerUserId(), agent.getTeamId());
    }

    private Task saveTask() {
        return tasks.save(Task.create(UUID.randomUUID().toString(), teamId, requester.getId(),
                requester.getOwnerUserId(), receiver.getId(), "ACK 테스트 작업"));
    }

    private void assertAcknowledgedTask(long taskId) {
        Task task = tasks.findById(taskId).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.getDeliveredAt()).isNotNull();
        assertThat(task.getVersion()).isEqualTo(1L);
        assertThat(history.findByTaskIdOrderByCreatedAtAsc(taskId))
                .extracting(com.devlink.server.domain.taskevent.TaskEvent::getEventType)
                .containsExactly(com.devlink.server.domain.taskevent.TaskEventType.ACK_RECEIVED);
    }

    @Test
    void ackHistoryFailureRollsBackDeliveredAtButCompletesObservedResult() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("ACK history failed"))
                .when(history).save(org.mockito.ArgumentMatchers.argThat(event -> event.getEventType()
                        == com.devlink.server.domain.taskevent.TaskEventType.ACK_RECEIVED));
        try (var client = HttpClient.newHttpClient(); var broker = connect(client, "receiver", receiver)) {
            broker.online();
            Task task = saveTask();
            var sent = routing.sendTask(task.getId());
            broker.next();
            broker.ack(task.getId(), sent.eventId());
            assertThat(nextResult().status()).isEqualTo(Status.ACKNOWLEDGED);
            assertUnchangedTask(task.getId());
            assertThat(history.findByTaskIdOrderByCreatedAtAsc(task.getId())).isEmpty();
        }
    }

    private void assertUnchangedTask(long taskId) {
        Task task = tasks.findById(taskId).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.getDeliveredAt()).isNull();
        assertThat(task.getVersion()).isZero();
    }

    private TaskAckResult nextResult() throws InterruptedException {
        TaskAckResult result = ackResults.results.poll(5, TimeUnit.SECONDS);
        assertThat(result).as("ACK 대기 종료 이벤트").isNotNull();
        return result;
    }

    private TestBroker connect(HttpClient client, String token, Agent agent) throws Exception {
        var listener = new BrokerListener();
        var socket = client.newWebSocketBuilder()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/broker"), listener)
                .get(5, TimeUnit.SECONDS);
        var broker = new TestBroker(agent.getId(), socket, listener);
        var ready = broker.next();
        assertThat(ready.path("type").asText()).isEqualTo("BROKER_READY");
        broker.connectionId = ready.path("payload").path("connection_id").asText();
        return broker;
    }

    private final class TestBroker implements AutoCloseable {
        private final long agentId;
        private final WebSocket socket;
        private final BrokerListener listener;
        private String connectionId;

        private TestBroker(long agentId, WebSocket socket, BrokerListener listener) {
            this.agentId = agentId;
            this.socket = socket;
            this.listener = listener;
        }

        private JsonNode next() throws Exception {
            String message = listener.messages.poll(5, TimeUnit.SECONDS);
            assertThat(message).as("서버 JSON 메시지").isNotNull();
            return mapper.readTree(message);
        }

        private void online() throws Exception {
            socket.sendText("{\"type\":\"AGENT_PRESENCE\",\"payload\":{\"status\":\"ONLINE\"}}", true)
                    .get(5, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(agents.findById(agentId).orElseThrow().getStatus()).isEqualTo(AgentStatus.ONLINE));
        }

        private void ack(long taskId, String eventId) throws Exception {
            socket.sendText(mapper.writeValueAsString(Map.of("type", "ACK", "payload",
                    Map.of("task_id", taskId, "event_id", eventId))), true).get(5, TimeUnit.SECONDS);
        }

        private void barrier() throws Exception {
            // ACK 다음에 보낸 PING의 응답을 기다려 앞선 ACK 메시지가 처리됐음을 확인한다.
            socket.sendText("{\"type\":\"HEARTBEAT\",\"payload\":{\"kind\":\"PING\",\"ping_id\":\"barrier\"}}", true)
                    .get(5, TimeUnit.SECONDS);
            assertThat(next().path("payload").path("kind").asText()).isEqualTo("PONG");
        }

        @Override
        public void close() {
            socket.abort();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(connections.findByAgentId(Long.toString(agentId))
                            .map(current -> current.getId().equals(connectionId)).orElse(false)).isFalse());
        }
    }

    private static class BrokerListener implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder text = new StringBuilder();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                messages.add(text.toString());
                text.setLength(0);
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }
    }

    static class AckResults {
        private final LinkedBlockingQueue<TaskAckResult> results = new LinkedBlockingQueue<>();

        @EventListener
        public void receive(TaskAckResult result) {
            results.add(result);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixture {
        @Bean
        AtomicReference<Map<String, AgentPrincipal>> verifiedAgents() {
            return new AtomicReference<>(Map.of());
        }

        @Bean
        AgentTokenVerifier agentTokenVerifier(AtomicReference<Map<String, AgentPrincipal>> verifiedAgents) {
            return token -> Optional.ofNullable(verifiedAgents.get().get(token));
        }

        @Bean
        AckResults ackResults() {
            return new AckResults();
        }
    }
}
