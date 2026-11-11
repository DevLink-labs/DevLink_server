package com.devlink.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.domain.outbox.OutboxEvent;
import com.devlink.server.domain.outbox.OutboxStatus;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.domain.taskfile.ChangeType;
import com.devlink.server.domain.taskfile.TaskFile;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.teammember.TeamMember;
import com.devlink.server.domain.user.User;
import com.devlink.server.dto.ChangeTaskStatusRequest;
import com.devlink.server.dto.task.TaskQuestionRequest;
import com.devlink.server.dto.task.TaskAnswerRequest;
import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.OutboxEventRepository;
import com.devlink.server.repository.TaskFileRepository;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TaskEventRepository;
import com.devlink.server.repository.TeamMemberRepository;
import com.devlink.server.repository.TeamRepository;
import com.devlink.server.repository.UserRepository;
import com.devlink.server.service.AgentTokenVerifier;
import com.devlink.server.service.OutboxPublicationService;
import com.devlink.server.service.OutboxPublisher;
import com.devlink.server.service.TaskAckService;
import com.devlink.server.service.TaskCompletionOutboxWriter;
import com.devlink.server.service.TaskStatusService;
import com.devlink.server.service.TaskNotificationService;
import com.devlink.server.service.TaskMessageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 실제 PostgreSQL의 행 잠금·복구·재시도와 실제 WebSocket 전송을 함께 검증한다. */
@TestPropertySource(properties = {"devlink.outbox.publisher-enabled=false", "devlink.outbox.batch-size=10",
        "devlink.outbox.lease-ms=1000", "devlink.outbox.retry-base-ms=100",
        "devlink.outbox.retry-max-ms=400", "devlink.outbox.max-attempts=3"})
@Import(TaskNotificationIntegrationTest.Fixture.class)
class TaskNotificationIntegrationTest extends AbstractIntegrationTest {
    @Autowired private TaskRepository tasks;
    @Autowired private TaskEventRepository events;
    @Autowired private TeamMemberRepository members;
    @Autowired private TaskMessageService messages;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AgentRepository agents;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private TaskFileRepository files;
    @Autowired private OutboxEventRepository outbox;
    @Autowired private OutboxPublisher publisher;
    @MockitoSpyBean private OutboxPublicationService publications;
    @Autowired private TaskStatusService statuses;
    @Autowired private TaskNotificationService notifications;
    @Autowired private TaskCompletionOutboxWriter completion;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ConnectionRegistry connections;
    @Autowired private ObjectMapper mapper;
    @Autowired private MutableClock clock;
    @Autowired private AtomicReference<Map<String, AgentPrincipal>> verifiedAgents;
    @MockitoSpyBean private TaskAckService acknowledgements;
    // 이 테스트는 outbox 행 전체를 세므로 팀 Context 갱신 신호는 끈다. 그 신호는 TeamContextIntegrationTest가 검증한다.
    @MockitoBean private com.devlink.server.service.TeamContextOutboxWriter teamContextOutbox;
    @LocalServerPort private int port;
    private Agent requester;
    private Agent receiver;
    private Long taskId;

    @BeforeEach
    void setup() {
        outbox.deleteAllInBatch();
        // DB가 보존하는 마이크로초 단위로 경계 시각을 고정해 만료 직전을 정확히 검증한다.
        clock.set(Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        String unique = UUID.randomUUID().toString();
        Long teamId = teams.save(Team.create("Outbox notification", unique)).getId();
        requester = createAgent(teamId, "requester-" + unique);
        receiver = createAgent(teamId, "receiver-" + unique);
        verifiedAgents.set(Map.of("requester", principal(requester), "receiver", principal(receiver)));
        taskId = tasks.save(Task.create(unique, teamId, requester.getId(), requester.getOwnerUserId(),
                receiver.getId(), "private prompt")).getId();
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"IN_PROGRESS", "REJECTED", "FAILED"})
    void realStatusServiceOutboxPublishesStoredJsonOnlyToRequesterWithoutAck(TaskStatus target) throws Exception {
        if (target == TaskStatus.FAILED) {
            change(TaskStatus.IN_PROGRESS, 0);
            var initial = publications.claimNext().orElseThrow();
            publications.published(initial.outboxId(), initial.lockToken());
        }
        try (var client = HttpClient.newHttpClient(); var req = connect(client, "requester", requester);
                var other = connect(client, "receiver", receiver)) {
            change(target, target == TaskStatus.FAILED ? 1 : 0);
            var stored = outbox.findAll().stream().filter(e -> e.getStatus() == OutboxStatus.PENDING).findFirst().orElseThrow();
            var original = stored.getMessage();

            assertThat(publisher.publishBatch()).isEqualTo(1);

            assertThat(req.next()).isEqualTo(original);
            assertThat(other.messages.poll(150, TimeUnit.MILLISECONDS)).isNull();
            var published = outbox.findById(stored.getOutboxId()).orElseThrow();
            assertThat(published.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            assertThat(published.getPublishedAt()).isEqualTo(clock.instant());
            assertThat(published.getAttemptCount()).isEqualTo(1);
            assertThat(published.getLockToken()).isNull();
            assertThat(tasks.findById(taskId).orElseThrow().getStatus()).isEqualTo(target);
            verify(acknowledgements, never()).register(anyLong(), anyString(), any(), any());
        }
    }

    @Test
    void laterStatusCannotRewriteEarlierStoredEnvelope() throws Exception {
        change(TaskStatus.IN_PROGRESS, 0);
        change(TaskStatus.FAILED, 1);
        var expected = outbox.findAll().stream().collect(java.util.stream.Collectors.toMap(
                e -> e.getOutboxId().toString(), OutboxEvent::getMessage));
        try (var client = HttpClient.newHttpClient(); var broker = connect(client, "requester", requester)) {
            assertThat(publisher.publishBatch()).isEqualTo(2);
            for (int index = 0; index < 2; index++) {
                var message = broker.next();
                assertThat(message).isEqualTo(expected.get(message.path("event_id").asText()));
            }
        }
    }

    @Test
    void offlineRetryKeepsSameMessageAndEventId() throws Exception {
        change(TaskStatus.REJECTED, 0);
        var first = outbox.findAll().getFirst();
        var original = first.getMessage();
        assertThat(publisher.publishBatch()).isEqualTo(1);
        var waiting = outbox.findById(first.getOutboxId()).orElseThrow();
        assertThat(waiting.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(waiting.getAttemptCount()).isEqualTo(1);
        assertThat(waiting.getLastError()).isEqualTo("CONNECTION_UNAVAILABLE");
        assertThat(waiting.getAvailableAt()).isEqualTo(clock.instant().plusMillis(100));
        assertThat(publisher.publishBatch()).isZero();
        clock.advance(100);
        try (var client = HttpClient.newHttpClient(); var broker = connect(client, "requester", requester)) {
            publisher.publishBatch();
            assertThat(broker.next()).isEqualTo(original);
        }
        assertThat(outbox.findById(first.getOutboxId()).orElseThrow().getAttemptCount()).isEqualTo(2);
        assertThat(tasks.findById(taskId).orElseThrow().getVersion()).isEqualTo(1);
    }

    @Test
    void retryLimitEndsInFailedAndDoesNotAutomaticallyRestart() {
        change(TaskStatus.IN_PROGRESS, 0);
        UUID id = outbox.findAll().getFirst().getOutboxId();
        publisher.publishBatch();
        clock.advance(100);
        publisher.publishBatch();
        clock.advance(200);
        publisher.publishBatch();
        var failed = outbox.findById(id).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(failed.getAttemptCount()).isEqualTo(3);
        assertThat(failed.getLockToken()).isNull();
        clock.advance(10000);
        assertThat(publisher.publishBatch()).isZero();
        assertThat(tasks.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    void expiredLeaseIsReclaimedAndPreviousTokenCannotUpdateAnyResult() {
        change(TaskStatus.IN_PROGRESS, 0);
        var previous = publications.claimNext().orElseThrow();
        assertThat(publications.claimNext()).isEmpty();
        clock.advance(1000);
        assertThat(publications.published(previous.outboxId(), previous.lockToken())).isFalse();
        var current = publications.claimNext().orElseThrow();
        assertThat(current.outboxId()).isEqualTo(previous.outboxId());
        assertThat(current.lockToken()).isNotEqualTo(previous.lockToken());
        assertThat(current.attemptCount()).isEqualTo(2);
        assertThat(publications.failed(previous.outboxId(), previous.lockToken(), "old failure", false)).isFalse();
        assertThat(publications.published(previous.outboxId(), previous.lockToken())).isFalse();
        assertThat(publications.published(current.outboxId(), current.lockToken())).isTrue();
    }

    @Test
    void abandonedFinalAttemptIsFailedAfterLeaseExpiry() {
        change(TaskStatus.IN_PROGRESS, 0);
        for (int index = 0; index < 3; index++) {
            assertThat(publications.claimNext()).isPresent();
            clock.advance(1000);
        }
        assertThat(publications.claimNext()).isEmpty();
        assertThat(outbox.findAll().getFirst().getStatus()).isEqualTo(OutboxStatus.FAILED);
    }

    @Test
    void twoWorkersCannotClaimSamePendingEvent() throws Exception {
        change(TaskStatus.IN_PROGRESS, 0);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return publications.claimNext(); });
            var second = executor.submit(() -> { start.await(); return publications.claimNext(); });
            start.countDown();
            var results = java.util.List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertThat(results.stream().filter(Optional::isPresent).count()).isEqualTo(1);
        }
        assertThat(outbox.findAll().getFirst().getAttemptCount()).isEqualTo(1);
    }

    @Test
    void lockedRowIsSkippedInsteadOfBlockingOtherWorker() {
        change(TaskStatus.IN_PROGRESS, 0);
        change(TaskStatus.FAILED, 1);
        var transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(tx -> {
            var locked = outbox.findNextTaskNotification(clock.instant()).getFirst();
            try (var executor = Executors.newSingleThreadExecutor()) {
                var claimed = executor.submit(publications::claimNext).get(5, TimeUnit.SECONDS).orElseThrow();
                assertThat(claimed.outboxId()).isNotEqualTo(locked.getOutboxId());
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    @Test
    void lostLeaseBeforeActualWriteDoesNotSendOrOverwriteNewOwner() throws Exception {
        change(TaskStatus.IN_PROGRESS, 0);
        var replacement = new AtomicReference<com.devlink.server.dto.OutboxDelivery>();
        doAnswer(call -> {
            clock.advance(1000);
            replacement.set(publications.claimNext().orElseThrow());
            return call.callRealMethod();
        }).when(publications).renewBeforeSend(any(), any());
        try (var client = HttpClient.newHttpClient(); var broker = connect(client, "requester", requester)) {
            publisher.publishBatch();
            assertThat(broker.messages.poll(150, TimeUnit.MILLISECONDS)).isNull();
        }
        var stored = outbox.findById(replacement.get().outboxId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
        assertThat(stored.getLockToken()).isEqualTo(replacement.get().lockToken());
    }

    @Test
    void successfulWriteBeforeLostCompletionRecordCanBeRetriedWithSameId() throws Exception {
        change(TaskStatus.IN_PROGRESS, 0);
        try (var client = HttpClient.newHttpClient(); var broker = connect(client, "requester", requester)) {
            var firstAttempt = publications.claimNext().orElseThrow();
            assertThat(notifications.sendNotification(firstAttempt, () ->
                    assertThat(publications.renewBeforeSend(firstAttempt.outboxId(), firstAttempt.lockToken())).isTrue())
                    .status()).isEqualTo(com.devlink.server.dto.TaskNotificationResult.Status.SENT);
            var first = broker.next();
            // 전송 후 완료 기록 전에 서버가 멈춘 상황을 시계 진행으로 재현한다.
            clock.advance(1000);
            assertThat(publications.published(firstAttempt.outboxId(), firstAttempt.lockToken())).isFalse();
            assertThat(outbox.findAll().getFirst().getStatus()).isEqualTo(OutboxStatus.PROCESSING);
            publisher.publishBatch();
            assertThat(broker.next()).isEqualTo(first);
        }
    }

    @Test
    void invalidMessageFailsPermanentlyAndUnrelatedDomainIsNotClaimed() {
        UUID invalidId = UUID.randomUUID();
        var invalid = mapper.createObjectNode().put("type", "TASK_STATUS_CHANGED").put("event_id", invalidId.toString());
        outbox.saveAndFlush(OutboxEvent.create(invalidId, "TASK_STATUS_CHANGED", "TASK", taskId.toString(),
                "task-events", "TASK:" + taskId, invalid));
        UUID teamId = UUID.randomUUID();
        outbox.saveAndFlush(OutboxEvent.create(teamId, "TEAM_CREATED", "TEAM", "team-1", "team-events", null,
                mapper.createObjectNode().put("type", "TEAM_CREATED").put("event_id", teamId.toString())));
        assertThat(publisher.publishBatch()).isEqualTo(1);
        assertThat(outbox.findById(invalidId).orElseThrow().getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(outbox.findById(teamId).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    void completionIsSavedInBusinessTransactionAndPublishedFromStoredMessage() throws Exception {
        change(TaskStatus.IN_PROGRESS, 0);
        outbox.deleteAllInBatch();
        UUID id = complete(false);
        var original = outbox.findById(id).orElseThrow().getMessage();
        assertThat(original.path("type").asText()).isEqualTo("TASK_COMPLETED");
        assertThat(original.path("task_id").asLong()).isEqualTo(taskId);
        assertThat(original.path("payload").path("result_text").asText()).isEqualTo("완료 결과");
        try (var client = HttpClient.newHttpClient(); var broker = connect(client, "requester", requester)) {
            publisher.publishBatch();
            assertThat(broker.next()).isEqualTo(original);
        }
        assertThat(tasks.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(files.findByTaskId(taskId)).hasSize(1);
    }

    @Test
    void completionRollbackRemovesResultFilesAndOutboxTogether() {
        change(TaskStatus.IN_PROGRESS, 0);
        outbox.deleteAllInBatch();
        UUID id = complete(true);
        assertThat(outbox.findById(id)).isEmpty();
        assertThat(tasks.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(files.findByTaskId(taskId)).isEmpty();
    }

    @Test
    void completionWriterRequiresBusinessTransactionAndPublisherRejectsOne() {
        assertThatThrownBy(() -> completion.enqueue(tasks.findById(taskId).orElseThrow()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(tx -> publisher.publishBatch()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void questionAndAnswerNotifyOppositeAgentsAndBrokerCanPullOriginalEvents(boolean requesterAsks) throws Exception {
        startConversation();
        Agent author = requesterAsks ? requester : receiver;
        Agent recipient = requesterAsks ? receiver : requester;
        String recipientToken = requesterAsks ? "receiver" : "requester";
        String authorToken = requesterAsks ? "requester" : "receiver";
        var request = new TaskQuestionRequest("question", "비공개 질문 본문");
        try (var client = HttpClient.newHttpClient(); var req = connect(client, "requester", requester);
                var other = connect(client, "receiver", receiver)) {
            var question = messages.createQuestion(taskId, author.getId(), request);
            assertThat(messages.createQuestion(taskId, author.getId(), request).replayed()).isTrue();
            assertThat(outbox.count()).isEqualTo(1);
            JsonNode storedQuestion = outbox.findAll().getFirst().getMessage();

            assertThat(publisher.publishBatch()).isEqualTo(1);

            Broker questionBroker = requesterAsks ? other : req;
            Broker answerBroker = requesterAsks ? req : other;
            assertThat(questionBroker.next()).isEqualTo(storedQuestion);
            assertThat(answerBroker.messages.poll(150, TimeUnit.MILLISECONDS)).isNull();
            assertThat(storedQuestion.path("type").asText()).isEqualTo("TASK_QUESTION_CREATED");
            assertThat(storedQuestion.path("receiver_agent_id").asLong()).isEqualTo(recipient.getId());
            assertThat(storedQuestion.path("payload").path("task_event_id").asText()).isEqualTo(question.eventId());
            assertThat(storedQuestion.has("version")).isFalse();
            assertThat(storedQuestion.toString()).doesNotContain("비공개 질문 본문");
            // 브로커가 알림을 받고 Pull하면 같은 원본 ID의 도착 정보를 받는다.
            JsonNode arrivals = pullMessages(recipientToken);
            assertThat(arrivals).hasSize(1);
            assertThat(arrivals.get(0).path("eventId").asText()).isEqualTo(question.eventId());
            assertThat(arrivals.get(0).path("sequence").asText()).isEqualTo(question.sequence());

            var answer = messages.createAnswer(taskId, recipient.getId(),
                    new TaskAnswerRequest("answer", "비공개 답변 본문", question.eventId()));
            assertThat(publisher.publishBatch()).isEqualTo(1);
            JsonNode notification = answerBroker.next();
            assertThat(notification.path("type").asText()).isEqualTo("TASK_ANSWER_CREATED");
            assertThat(notification.path("receiver_agent_id").asLong()).isEqualTo(author.getId());
            assertThat(notification.path("payload").path("task_event_id").asText()).isEqualTo(answer.eventId());
            assertThat(questionBroker.messages.poll(150, TimeUnit.MILLISECONDS)).isNull();
            assertThat(pullMessages(authorToken).get(0).path("eventId").asText()).isEqualTo(answer.eventId());
            assertThat(outbox.findAll()).allSatisfy(event -> assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED));
            assertThat(tasks.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
            verify(acknowledgements, never()).register(anyLong(), anyString(), any(), any());
        }
    }

    @Test
    void questionRetryAfterReconnectKeepsStoredMessageAndOriginalEventId() throws Exception {
        startConversation();
        var question = messages.createQuestion(taskId, requester.getId(), new TaskQuestionRequest("q", "Question"));
        var original = outbox.findAll().getFirst();
        assertThat(publisher.publishBatch()).isEqualTo(1);
        var pending = outbox.findById(original.getOutboxId()).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(pending.getLastError()).isEqualTo("CONNECTION_UNAVAILABLE");
        assertThat(pullMessages("receiver").get(0).path("eventId").asText()).isEqualTo(question.eventId());
        assertThat(publisher.publishBatch()).isZero();

        clock.advance(100);
        try (var client = HttpClient.newHttpClient(); var broker = connect(client, "receiver", receiver)) {
            assertThat(publisher.publishBatch()).isEqualTo(1);
            assertThat(broker.next()).isEqualTo(original.getMessage());
        }
        var published = outbox.findById(original.getOutboxId()).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(published.getAttemptCount()).isEqualTo(2);
    }

    @Test
    void failedNotificationDoesNotPreventQuestionRecovery() {
        startConversation();
        var question = messages.createQuestion(taskId, requester.getId(), new TaskQuestionRequest("q", "Question"));
        publisher.publishBatch();
        clock.advance(100);
        publisher.publishBatch();
        clock.advance(200);
        publisher.publishBatch();

        assertThat(outbox.findAll().getFirst().getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(pullMessages("receiver").get(0).path("eventId").asText()).isEqualTo(question.eventId());
    }

    @Test
    void questionSavedBeforeTaskCompletionIsStillPublished() throws Exception {
        startConversation();
        messages.createQuestion(taskId, requester.getId(), new TaskQuestionRequest("q", "Question"));
        var stored = outbox.findAll().getFirst().getMessage();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                tasks.findByIdForUpdate(taskId).orElseThrow().complete(receiver.getId(), "Done"));

        try (var client = HttpClient.newHttpClient(); var broker = connect(client, "receiver", receiver)) {
            assertThat(publisher.publishBatch()).isEqualTo(1);
            assertThat(broker.next()).isEqualTo(stored);
        }
        assertThat(tasks.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    void notificationAddressedToAuthorFailsWithoutLeakingToEitherSocket() throws Exception {
        startConversation();
        var question = messages.createQuestion(taskId, requester.getId(), new TaskQuestionRequest("q", "Question"));
        var stored = outbox.findAll().getFirst();
        // 저장된 JSON의 수신자가 잘못된 상황을 재현하고 원본 작성자와 대조한다.
        jdbc.update("UPDATE outbox_events SET message = jsonb_set(message, '{receiver_agent_id}', to_jsonb(CAST(? AS bigint))) WHERE outbox_id = ?",
                requester.getId(), stored.getOutboxId());
        try (var client = HttpClient.newHttpClient(); var req = connect(client, "requester", requester);
                var other = connect(client, "receiver", receiver)) {
            assertThat(publisher.publishBatch()).isEqualTo(1);
            assertThat(req.messages.poll(150, TimeUnit.MILLISECONDS)).isNull();
            assertThat(other.messages.poll(150, TimeUnit.MILLISECONDS)).isNull();
        }
        var failed = outbox.findById(stored.getOutboxId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(failed.getLastError()).isEqualTo("INVALID_EVENT");
        assertThat(pullMessages("receiver").get(0).path("eventId").asText()).isEqualTo(question.eventId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void messageIsInvisibleToPublisherUntilCommitAndRollbackRemovesIt(boolean rollback) throws Exception {
        startConversation();
        long eventCount = events.count();
        try (var client = HttpClient.newHttpClient(); var broker = connect(client, "receiver", receiver)) {
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                messages.createQuestion(taskId, requester.getId(), new TaskQuestionRequest("q", "Question"));
                // 다른 트랜잭션의 발행기는 미커밋 Outbox를 가져가면 안 된다.
                try (var executor = Executors.newSingleThreadExecutor()) {
                    assertThat(executor.submit(publisher::publishBatch).get(5, TimeUnit.SECONDS)).isZero();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
                if (rollback) tx.setRollbackOnly();
            });
            assertThat(publisher.publishBatch()).isEqualTo(rollback ? 0 : 1);
            if (rollback) {
                assertThat(broker.messages.poll(150, TimeUnit.MILLISECONDS)).isNull();
                assertThat(outbox.count()).isZero();
                assertThat(events.count()).isEqualTo(eventCount);
            } else {
                assertThat(broker.next().path("type").asText()).isEqualTo("TASK_QUESTION_CREATED");
            }
        }
    }

    private void startConversation() {
        change(TaskStatus.IN_PROGRESS, 0);
        outbox.deleteAllInBatch();
    }

    private JsonNode pullMessages(String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        var response = rest.exchange("/api/v1/agents/me/task-messages", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().path("data").path("items");
    }

    private UUID complete(boolean rollback) {
        return new TransactionTemplate(transactionManager).execute(tx -> {
            Task task = tasks.findById(taskId).orElseThrow();
            task.complete(receiver.getId(), "완료 결과");
            files.save(TaskFile.of(taskId, "src/Example.java", ChangeType.MODIFIED, "diff"));
            UUID id = completion.enqueue(task);
            if (rollback) tx.setRollbackOnly();
            return id;
        });
    }

    private void change(TaskStatus target, long version) {
        statuses.changeStatus(taskId, receiver.getId(), new ChangeTaskStatusRequest(target, version,
                target == TaskStatus.REJECTED ? "거절 사유" : null,
                target == TaskStatus.FAILED ? "실패 사유" : null, target == TaskStatus.FAILED ? false : null));
    }

    private Agent createAgent(Long teamId, String name) {
        User user = users.save(User.create(name + "@outbox.test", "Outbox", null, "hash"));
        members.save(TeamMember.create(teamId, user.getId(), MemberRole.MEMBER));
        return agents.save(Agent.create(teamId, user.getId(), name, null, ToolType.CODEX, "server"));
    }

    private AgentPrincipal principal(Agent agent) {
        return new AgentPrincipal(agent.getId(), agent.getOwnerUserId(), agent.getTeamId());
    }

    private Broker connect(HttpClient client, String token, Agent agent) throws Exception {
        var broker = new Broker(agent.getId());
        broker.socket = client.newWebSocketBuilder().header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/broker"), broker).get(5, TimeUnit.SECONDS);
        assertThat(broker.next().path("type").asText()).isEqualTo("BROKER_READY");
        return broker;
    }

    private final class Broker implements WebSocket.Listener, AutoCloseable {
        private final long agentId;
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder text = new StringBuilder();
        private WebSocket socket;
        private Broker(long agentId) { this.agentId = agentId; }
        @Override public void onOpen(WebSocket ws) { ws.request(1); }
        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            text.append(data);
            if (last) { messages.add(text.toString()); text.setLength(0); }
            ws.request(1);
            return CompletableFuture.completedFuture(null);
        }
        private JsonNode next() throws Exception {
            String message = messages.poll(5, TimeUnit.SECONDS);
            assertThat(message).isNotNull();
            return mapper.readTree(message);
        }
        @Override public void close() {
            socket.abort();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(connections.findByAgentId(Long.toString(agentId))).isEmpty());
        }
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> time = new AtomicReference<>(Instant.now());
        void set(Instant now) { time.set(now); }
        void advance(long millis) { time.updateAndGet(now -> now.plusMillis(millis)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return time.get(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixture {
        @Bean MutableClock outboxTestClock() { return new MutableClock(); }
        @Bean AtomicReference<Map<String, AgentPrincipal>> verifiedAgents() { return new AtomicReference<>(Map.of()); }
        @Bean AgentTokenVerifier agentTokenVerifier(AtomicReference<Map<String, AgentPrincipal>> verifiedAgents) {
            return token -> Optional.ofNullable(verifiedAgents.get().get(token));
        }
    }
}
