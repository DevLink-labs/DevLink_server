package com.devlink.server;

import com.devlink.server.common.BusinessException;
import com.devlink.server.domain.UserPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.teammember.TeamMember;
import com.devlink.server.domain.user.User;
import com.devlink.server.dto.task.TaskEventListRequest;
import com.devlink.server.realtime.WebEventEntityListener.TaskChanged;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskEventRepository;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TeamMemberRepository;
import com.devlink.server.repository.TeamRepository;
import com.devlink.server.repository.UserRepository;
import com.devlink.server.service.AgentTokenService;
import com.devlink.server.service.TaskQueryService;
import com.devlink.server.service.WebEventStreamService;
import java.util.UUID;
import java.util.Map;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WebEventIntegrationTest extends AbstractIntegrationTest {
    private static final String PASSWORD = "ValidPassword123!";
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    private String email;
    @Autowired TestRestTemplate rest;
    @Autowired UserRepository users;
    @Autowired TeamRepository teams;
    @Autowired TeamMemberRepository members;
    @Autowired AgentRepository agents;
    @Autowired TaskRepository tasks;
    @Autowired TaskEventRepository events;
    @Autowired AgentTokenService tokens;
    @Autowired TaskQueryService queries;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean WebEventStreamService streams;
    private Long userId;
    private Long teamId;
    private Long agentId;
    private Long taskId;

    @BeforeEach
    void setUp() {
        var key = UUID.randomUUID().toString();
        email = key + "@test.com";
        userId = users.save(User.create(email, "SSE", null, passwords.encode(PASSWORD))).getId();
        teamId = teams.save(Team.create("SSE", key)).getId();
        members.save(TeamMember.create(teamId, userId, MemberRole.OWNER));
        agentId = agents.save(Agent.create(teamId, userId, "SSE Agent", null, ToolType.CODEX, "project")).getId();
        taskId = tasks.save(Task.create(key, teamId, agentId, userId, agentId, "prompt")).getId();
        doNothing().when(streams).taskChanged(any(TaskChanged.class));
        clearInvocations(streams);
    }

    @Test
    void authenticatedSessionReceivesOnlyCommittedMessagesOverTheRealStream() throws Exception {
        doCallRealMethod().when(streams).taskChanged(any(TaskChanged.class));
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (var client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).build()) {
            login(client);
            var response = client.send(HttpRequest.newBuilder(uri("/api/v1/web/events"))
                    .timeout(Duration.ofSeconds(10)).header("Accept", "text/event-stream").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
            try (var frames = new StreamFrames(response.body())) {
                assertThat(frames.next(Duration.ofSeconds(5))).contains("event:ready");
                var transaction = new TransactionTemplate(transactions);
                transaction.executeWithoutResult(status -> {
                    events.saveAndFlush(TaskEvent.of(taskId, TaskEventType.PROGRESS, "live committed message"));
                    assertThat(frames.next(Duration.ofMillis(200))).isNull();
                });
                String frame = frames.next(Duration.ofSeconds(5));
                assertThat(frame).contains("event:task-updated");
                var payload = frame.lines().filter(line -> line.startsWith("data:"))
                        .map(line -> line.substring(5)).collect(Collectors.joining("\n"));
                var message = json.readTree(payload);
                assertThat(message.path("task").path("taskId").asLong()).isEqualTo(taskId);
                assertThat(message.path("event").path("message").asText()).isEqualTo("live committed message");
                assertThat(message.path("event").path("sequence").asText()).isEqualTo("1");
                transaction.executeWithoutResult(status -> {
                    events.saveAndFlush(TaskEvent.of(taskId, TaskEventType.PROGRESS, "rolled back live message"));
                    status.setRollbackOnly();
                });
                assertThat(frames.next(Duration.ofMillis(300))).isNull();
            }
        }
    }

    private void login(HttpClient client) throws Exception {
        var csrfResponse = client.send(HttpRequest.newBuilder(uri("/api/v1/auth/csrf"))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(csrfResponse.statusCode()).isEqualTo(200);
        var csrf = json.readTree(csrfResponse.body()).path("data");
        var login = HttpRequest.newBuilder(uri("/api/v1/auth/login")).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        Map.of("email", email, "password", PASSWORD)))).build();
        assertThat(client.send(login, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static final class StreamFrames implements AutoCloseable {
        private final InputStream body;
        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final Thread reader;
        private volatile boolean closed;

        private StreamFrames(InputStream body) {
            this.body = body;
            reader = Thread.ofVirtual().start(() -> {
                try (var lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                    var frame = new StringBuilder();
                    String line;
                    while ((line = lines.readLine()) != null) {
                        if (line.isEmpty()) {
                            if (!frame.isEmpty()) frames.offer(frame.toString());
                            frame.setLength(0);
                        } else if (!line.startsWith(":")) frame.append(line).append('\n');
                    }
                } catch (IOException exception) {
                    if (!closed) frames.offer("stream-error:" + exception.getMessage());
                }
            });
        }

        private String next(Duration timeout) {
            try {
                return frames.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for SSE", exception);
            }
        }

        @Override
        public void close() throws Exception {
            closed = true;
            body.close();
            reader.interrupt();
            reader.join(1_000);
        }
    }

    @Test
    void anonymousAndAgentTokenRequestsCannotSubscribe() {
        assertThat(rest.getForEntity("/api/v1/web/events", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var headers = new HttpHeaders();
        headers.setBearerAuth(tokens.issue(userId, agentId).token());
        var response = rest.exchange("/api/v1/web/events", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void persistedEventsArePublishedOnlyAfterSuccessfulCommit() {
        var transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(status -> {
            events.saveAndFlush(TaskEvent.of(taskId, TaskEventType.PROGRESS, "committed"));
            verify(streams, never()).taskChanged(any(TaskChanged.class));
        });
        verify(streams).taskChanged(argThat(change -> change.taskId().equals(taskId) && change.eventId() != null));
        clearInvocations(streams);
        transaction.executeWithoutResult(status -> {
            events.saveAndFlush(TaskEvent.of(taskId, TaskEventType.PROGRESS, "rolled back"));
            status.setRollbackOnly();
        });
        verify(streams, never()).taskChanged(any(TaskChanged.class));
        assertThat(events.findByTaskIdOrderBySequenceAsc(taskId)).extracting(TaskEvent::getContent).containsExactly("committed");
    }

    @Test
    void afterSequencePagesRecoverMissingRecordsWithoutReadingOldPages() {
        for (int index = 0; index < 3; index++) events.saveAndFlush(TaskEvent.of(taskId, TaskEventType.PROGRESS, "record " + index));
        var request = new TaskEventListRequest(null, 2);
        var first = queries.getWebTaskHistory(teamId, taskId, new UserPrincipal(userId), request, "0");
        assertThat(first.items()).hasSize(2);
        assertThat(first.hasNext()).isTrue();
        assertThat(first.nextBeforeSequence()).isNull();
        assertThat(first.nextAfterSequence()).isEqualTo(first.items().getLast().sequence());
        var second = queries.getWebTaskHistory(teamId, taskId, new UserPrincipal(userId), request, first.nextAfterSequence());
        assertThat(second.items()).hasSize(1);
        assertThat(second.hasNext()).isFalse();
        assertThatThrownBy(() -> queries.getWebTaskHistory(teamId, taskId, new UserPrincipal(userId),
                new TaskEventListRequest("3", 2), "0")).isInstanceOf(BusinessException.class);
    }
}
