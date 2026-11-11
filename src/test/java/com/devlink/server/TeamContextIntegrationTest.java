package com.devlink.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import com.devlink.server.client.StructuralTeamContextInferenceClient;
import com.devlink.server.common.AgentTokenHash;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.AgentPart;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.domain.outbox.OutboxEvent;
import com.devlink.server.domain.outbox.OutboxStatus;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.teammember.TeamMember;
import com.devlink.server.domain.user.User;
import com.devlink.server.dto.ChangeTaskStatusRequest;
import com.devlink.server.dto.OutboxDelivery;
import com.devlink.server.dto.task.TaskAnswerRequest;
import com.devlink.server.dto.task.TaskQuestionRequest;
import com.devlink.server.dto.teamcontext.TeamContextSummary;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.OutboxEventRepository;
import com.devlink.server.repository.TaskEventRepository;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TeamMemberRepository;
import com.devlink.server.repository.TeamPartContextRepository;
import com.devlink.server.repository.TeamRepository;
import com.devlink.server.repository.UserRepository;
import com.devlink.server.service.TaskMessageService;
import com.devlink.server.service.TaskStatusService;
import com.devlink.server.service.TeamContextOutboxService;
import com.devlink.server.service.TeamContextOutboxWriter;
import com.devlink.server.service.TeamContextStore;
import com.devlink.server.service.TeamContextUpdater;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 작업 생성·상태 변경·질문·답변이 team-context Outbox를 남기고, 갱신기가 파트 쌍 요약을 버전으로 쌓으며,
 * Agent 토큰으로 조회하는 전체 흐름을 실제 PostgreSQL로 검증한다. 테스트 프로필은 구조 신호 전용 추론을 쓴다.
 */
class TeamContextIntegrationTest extends AbstractIntegrationTest {
    private static final SecureRandom RANDOM = new SecureRandom();

    @LocalServerPort private int port;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private TeamMemberRepository members;
    @Autowired private AgentRepository agents;
    @Autowired private TaskRepository tasks;
    @Autowired private TaskEventRepository events;
    @Autowired private OutboxEventRepository outbox;
    @Autowired private TeamPartContextRepository contexts;
    @Autowired private TeamContextOutboxWriter writer;
    @Autowired private TeamContextOutboxService outboxService;
    @Autowired private TeamContextStore store;
    @Autowired private TeamContextUpdater updater;
    @Autowired private TaskStatusService statuses;
    @Autowired private TaskMessageService messages;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper json;

    private TransactionTemplate tx;
    private Team team;
    private Team otherTeam;
    private TestAgent frontend;
    private TestAgent backend;
    private TestAgent outsider;
    private Long taskId;

    @BeforeEach
    void prepare() {
        tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            outbox.deleteAllInBatch();
            contexts.deleteAllInBatch();
            team = teams.save(Team.create("Context 팀", UUID.randomUUID().toString()));
            otherTeam = teams.save(Team.create("다른 팀", UUID.randomUUID().toString()));
            User owner = user("프론트");
            User member = user("백엔드");
            User stranger = user("외부인");
            members.save(TeamMember.create(team.getId(), owner.getId(), MemberRole.OWNER));
            members.save(TeamMember.create(team.getId(), member.getId(), MemberRole.MEMBER));
            members.save(TeamMember.create(otherTeam.getId(), stranger.getId(), MemberRole.OWNER));
            frontend = agent(team, owner, "프론트 Agent", AgentPart.FRONTEND);
            backend = agent(team, member, "백엔드 Agent", AgentPart.BACKEND);
            outsider = agent(otherTeam, stranger, "외부 Agent", AgentPart.OTHER);
            Task task = tasks.save(Task.create(UUID.randomUUID().toString(), team.getId(), frontend.id(),
                    owner.getId(), backend.id(),
                    "{\"title\":\"delivery null 수정\",\"description\":\"완료 응답의 `delivery`가 null로 내려온다\"}",
                    "delivery null 수정"));
            taskId = task.getId();
            TaskEvent created = events.save(TaskEvent.of(taskId, TaskEventType.CREATED, null));
            writer.enqueue(task, created);
        });
    }

    @Test
    void outboxDrivenUpdatesBuildVersionsAndAgentsReadTheLatestSummary() throws Exception {
        // 1) 생성 이벤트 → 버전 1, 쟁점 없음
        assertThat(updater.processBatch()).isEqualTo(1);
        var first = latest();
        assertThat(first.getVersion()).isEqualTo(1);
        assertThat(first.getPartA()).isEqualTo(AgentPart.BACKEND);
        assertThat(first.getPartB()).isEqualTo(AgentPart.FRONTEND);
        assertThat(first.getModel()).isEqualTo(StructuralTeamContextInferenceClient.MODEL);
        assertThat(first.getPromptVersion()).isEqualTo("v1");
        assertThat(first.getIssues()).isEmpty();

        // 2) 실제 상태 서비스가 남긴 신호 → 버전 2
        statuses.changeStatus(taskId, backend.id(), new ChangeTaskStatusRequest(TaskStatus.IN_PROGRESS, 0L,
                null, null, null));
        assertThat(updater.processBatch()).isEqualTo(1);
        assertThat(latest().getVersion()).isEqualTo(2);

        // 3) 질문 → 버전 3, 미답변 질문이 쟁점으로 올라온다
        var question = messages.createQuestion(taskId, frontend.id(),
                new TaskQuestionRequest("q-1", "`delivery`가 왜 null로 오는지? HTTP 404 대신 빈 200인지 확인해줘"));
        assertThat(updater.processBatch()).isEqualTo(1);
        var third = latest();
        assertThat(third.getVersion()).isEqualTo(3);
        assertThat(third.getLastEventId()).isEqualTo(Long.parseLong(question.eventId()));
        assertThat(third.getIssues()).hasSize(1);
        String reference = taskId + ":" + question.eventId();
        assertThat(third.getIssues().get(0).path("topic").asText())
                .startsWith(StructuralTeamContextInferenceClient.QUESTION_PREFIX);
        assertThat(third.getIssues().get(0).path("evidence").get(0).asText()).isEqualTo(reference);
        assertThat(third.getIssues().get(0).path("positions").get(0).path("part").asText()).isEqualTo("FRONTEND");

        // 4) 답변 → 버전 4, 질문 쟁점이 닫힌다
        var answer = messages.createAnswer(taskId, backend.id(),
                new TaskAnswerRequest("a-1", "11/5부터 항상 채운다. 미존재 리소스는 404다.", question.eventId()));
        assertThat(updater.processBatch()).as("team-context rows: %s", describeRows()).isEqualTo(1);
        var fourth = latest();
        assertThat(fourth.getVersion()).isEqualTo(4);
        assertThat(fourth.getLastEventId()).isEqualTo(Long.parseLong(answer.eventId()));
        assertThat(fourth.getIssues()).isEmpty();
        assertThat(contexts.count()).isEqualTo(4);

        // 5) 같은 이벤트를 다시 넣으면 새 버전 없이 PUBLISHED로 닫힌다
        tx.executeWithoutResult(status -> writer.enqueue(tasks.findById(taskId).orElseThrow(),
                events.findById(Long.parseLong(answer.eventId())).orElseThrow()));
        assertThat(updater.processBatch()).isEqualTo(1);
        assertThat(contexts.count()).isEqualTo(4);
        assertThat(teamContextRows()).allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(OutboxStatus.PUBLISHED));

        // 6) 가리키는 작업이 없는 메시지는 영구 실패로 기록되고 요약은 그대로다
        UUID invalid = UUID.randomUUID();
        var envelope = json.createObjectNode().put("event_id", invalid.toString())
                .put("type", TeamContextOutboxWriter.TYPE).put("team_id", team.getId()).put("task_id", 999_999L);
        envelope.putObject("payload").put("task_event_id", 1L).put("event_type", "CREATED");
        tx.executeWithoutResult(status -> outbox.save(OutboxEvent.create(invalid, TeamContextOutboxWriter.TYPE,
                TeamContextOutboxWriter.AGGREGATE_TYPE, team.getId().toString(), TeamContextOutboxWriter.DESTINATION,
                null, envelope)));
        assertThat(updater.processBatch()).isEqualTo(1);
        var failed = outbox.findById(invalid).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(failed.getLastError()).isEqualTo("INVALID_EVENT");
        assertThat(contexts.count()).isEqualTo(4);
        assertThat(updater.processBatch()).isZero();

        // 7) Agent 토큰 조회: 쌍 지정, 전체, 빈 쌍
        JsonNode pair = assertSuccess(get(team.getId(), frontend.token(), "part=frontend&counterpart=BACKEND"));
        assertThat(pair.path("teamId").asLong()).isEqualTo(team.getId());
        assertThat(pair.path("part").asText()).isEqualTo("FRONTEND");
        assertThat(pair.path("contexts")).hasSize(1);
        JsonNode view = pair.path("contexts").get(0);
        assertThat(view.path("counterpart").asText()).isEqualTo("BACKEND");
        assertThat(view.path("version").asLong()).isEqualTo(4);
        assertThat(view.path("lastEventId").asLong()).isEqualTo(Long.parseLong(answer.eventId()));
        assertThat(view.path("model").asText()).isEqualTo(StructuralTeamContextInferenceClient.MODEL);
        assertThat(view.path("issues").isArray()).isTrue();
        assertThat(view.path("issues")).isEmpty();

        JsonNode all = assertSuccess(get(team.getId(), backend.token(), "part=BACKEND"));
        assertThat(all.path("contexts")).hasSize(1);
        assertThat(all.path("contexts").get(0).path("counterpart").asText()).isEqualTo("FRONTEND");

        JsonNode empty = assertSuccess(get(team.getId(), backend.token(), "part=INFRA&counterpart=OTHER"));
        assertThat(empty.path("contexts").get(0).path("version").asLong()).isZero();
        assertThat(empty.path("contexts").get(0).path("lastEventId").isNull()).isTrue();
        assertThat(empty.path("contexts").get(0).path("common")).isEmpty();
        assertThat(assertSuccess(get(team.getId(), backend.token(), "part=INFRA")).path("contexts")).isEmpty();

        // 8) 인증·검증 오류
        assertError(get(team.getId(), null, "part=FRONTEND"), 401, "AUTHENTICATION_REQUIRED");
        assertError(get(team.getId(), outsider.token(), "part=FRONTEND"), 403, "ACCESS_DENIED");
        assertError(get(otherTeam.getId(), frontend.token(), "part=FRONTEND"), 403, "ACCESS_DENIED");
        assertError(get(team.getId(), frontend.token(), ""), 400, "VALIDATION_FAILED");
        assertError(get(team.getId(), frontend.token(), "part=DB"), 400, "VALIDATION_FAILED");
        assertError(get(team.getId(), frontend.token(), "part=FRONTEND&counterpart=db"), 400, "VALIDATION_FAILED");
    }

    @Test
    void failedStatusAddsAnIssueAndWriterRowsStayOutOfTaskAggregates() {
        statuses.changeStatus(taskId, backend.id(), new ChangeTaskStatusRequest(TaskStatus.IN_PROGRESS, 0L,
                null, null, null));
        statuses.changeStatus(taskId, backend.id(), new ChangeTaskStatusRequest(TaskStatus.FAILED, 1L,
                null, "의존 API 미제공", false));
        List<OutboxEvent> rows = teamContextRows();
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getAggregateType()).isEqualTo(TeamContextOutboxWriter.AGGREGATE_TYPE);
            assertThat(row.getAggregateId()).isEqualTo(team.getId().toString());
            assertThat(row.getMessage().path("task_id").asLong()).isEqualTo(taskId);
            assertThat(row.getMessage().path("payload").path("task_event_id").asLong()).isPositive();
        });
        assertThat(updater.processBatch()).isEqualTo(3);
        var context = latest();
        assertThat(context.getVersion()).isEqualTo(3);
        assertThat(context.getIssues()).hasSize(1);
        assertThat(context.getIssues().get(0).path("topic").asText())
                .isEqualTo(StructuralTeamContextInferenceClient.FAILED_PREFIX + "IN_PROGRESS → FAILED: 의존 API 미제공");
        assertThat(context.getIssues().get(0).path("positions").get(0).path("part").asText()).isEqualTo("BACKEND");
    }

    @Test
    void aStaleInferenceRetriesAndAnEarlierUnappliedEventIsNotTreatedAsADuplicate() {
        var earlierDelivery = OutboxDelivery.from(teamContextRows().getFirst());
        var earlier = store.prepare(earlierDelivery).orElseThrow();
        statuses.changeStatus(taskId, backend.id(), new ChangeTaskStatusRequest(TaskStatus.IN_PROGRESS, 0L,
                null, null, null));
        var laterDelivery = teamContextRows().stream().map(OutboxDelivery::from)
                .filter(delivery -> !delivery.outboxId().equals(earlierDelivery.outboxId())).findFirst().orElseThrow();
        var later = store.prepare(laterDelivery).orElseThrow();
        store.save(later, TeamContextSummary.empty(), "structural");

        assertThatThrownBy(() -> store.save(earlier, TeamContextSummary.empty(), "structural"))
                .isInstanceOf(OptimisticLockingFailureException.class);
        var refreshed = store.prepare(earlierDelivery).orElseThrow();
        assertThat(refreshed.request().previousVersion()).isEqualTo(1);
        store.save(refreshed, TeamContextSummary.empty(), "structural");
        assertThat(latest().getLastEventId()).isEqualTo(earlier.eventId());
        assertThat(store.prepare(laterDelivery)).isEmpty();
        assertThat(contexts.count()).isEqualTo(2);
    }

    @Test
    void savesSummaryAndMarksOutboxAppliedInTheSameTransaction() {
        var delivery = outboxService.claimNext().orElseThrow();
        var prepared = store.prepare(delivery).orElseThrow();
        assertThatThrownBy(() -> outboxService.applied(delivery.outboxId(), delivery.lockToken(), () -> {
            store.save(prepared, TeamContextSummary.empty(), "structural");
            throw new IllegalStateException("completion failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(contexts.count()).isZero();
        assertThat(outbox.findById(delivery.outboxId()).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PROCESSING);
        assertThat(outboxService.applied(delivery.outboxId(), delivery.lockToken(),
                () -> store.save(prepared, TeamContextSummary.empty(), "structural"))).isTrue();
        assertThat(latest().getVersion()).isEqualTo(1);
        assertThat(outbox.findById(delivery.outboxId()).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    private com.devlink.server.domain.teamcontext.TeamPartContext latest() {
        return contexts.findFirstByTeamIdAndPartAAndPartBOrderByVersionDesc(team.getId(), AgentPart.BACKEND,
                AgentPart.FRONTEND).orElseThrow();
    }

    private List<String> describeRows() {
        return teamContextRows().stream()
                .map(row -> row.getStatus() + "/" + row.getAttemptCount() + "/" + row.getAvailableAt() + "/"
                        + row.getLockedUntil() + "/" + row.getLastError() + "/" + row.getMessage().path("payload"))
                .toList();
    }

    private List<OutboxEvent> teamContextRows() {
        return outbox.findAll().stream()
                .filter(row -> TeamContextOutboxWriter.DESTINATION.equals(row.getDestination()))
                .toList();
    }

    private HttpResponse<String> get(Long teamId, String token, String query) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/teams/" + teamId + "/context"
                        + (query.isEmpty() ? "" : "?" + query)))
                .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private JsonNode assertSuccess(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as("HTTP response: %s", response.body()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("data").isObject()).isTrue();
        return body.path("data");
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as("HTTP response: %s", response.body()).isEqualTo(status);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("code").asText()).isEqualTo(code);
    }

    private User user(String name) {
        return users.save(User.create(UUID.randomUUID() + "@context.test", name, null, "password-hash"));
    }

    private TestAgent agent(Team targetTeam, User user, String name, AgentPart part) {
        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Agent agent = Agent.create(targetTeam.getId(), user.getId(), name, null, ToolType.CODEX, "context-test",
                part, List.of());
        agent.issueConnectionToken(AgentTokenHash.sha256(token));
        agent = agents.saveAndFlush(agent);
        return new TestAgent(agent.getId(), token);
    }

    private record TestAgent(Long id, String token) { }
}
