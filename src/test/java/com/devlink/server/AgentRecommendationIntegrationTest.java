package com.devlink.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import com.devlink.server.common.AgentTokenHash;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.AgentPart;
import com.devlink.server.domain.agent.AgentStatus;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.domain.gitrepo.CommitChangeType;
import com.devlink.server.domain.gitrepo.CommitFile;
import com.devlink.server.domain.gitrepo.RepositoryCommit;
import com.devlink.server.domain.gitrepo.TeamGitRepository;
import com.devlink.server.domain.gitrepo.WorkingFile;
import com.devlink.server.domain.gitrepo.WorkingFileChangeType;
import com.devlink.server.domain.gitrepo.WorkingFileEntry;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.teammember.TeamMember;
import com.devlink.server.domain.user.User;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.CommitFileRepository;
import com.devlink.server.repository.FileDependencyRepository;
import com.devlink.server.repository.FileDependencyRepository.ImportingFileView;
import com.devlink.server.repository.RepositoryCommitRepository;
import com.devlink.server.repository.TeamGitRepositoryRepository;
import com.devlink.server.repository.TeamMemberRepository;
import com.devlink.server.repository.TeamRepository;
import com.devlink.server.repository.UserRepository;
import com.devlink.server.repository.WorkingFileRepository;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TaskEventRepository;
import com.devlink.server.realtime.ConnectionRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

/** 담당 Agent 추천 API를 실제 PostgreSQL과 Agent 토큰 인증으로 검증한다. */
class AgentRecommendationIntegrationTest extends AbstractIntegrationTest {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String QUESTION = "로그인을 처리하는 인증 흐름에서 refreshToken을 어떻게 반환해?";
    private static final String RELATED_PATH = "src/auth/RefreshTokenController.java";

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FileDependencyRepository dependencies;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private TeamMemberRepository members;
    @Autowired private AgentRepository agents;
    @Autowired private TeamGitRepositoryRepository repositories;
    @Autowired private RepositoryCommitRepository commits;
    @Autowired private CommitFileRepository commitFiles;
    @Autowired private WorkingFileRepository workingFiles;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper json;
    @Autowired private ConnectionRegistry connections;
    @Autowired private TaskRepository tasks;
    @Autowired private TaskEventRepository taskEvents;

    private Team team;
    private Team otherTeam;
    private TestAgent requester;
    private TestAgent agentB;
    private TestAgent agentC;
    private TestAgent agentD;
    private TestAgent otherTeamAgent;
    private Long repoId;
    private Instant now;

    @BeforeEach
    void prepareTeam() {
        now = Instant.now();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            team = teams.save(Team.create("추천 테스트 팀", UUID.randomUUID().toString()));
            otherTeam = teams.save(Team.create("다른 팀", UUID.randomUUID().toString()));
            User owner = createUser("요청자");
            User userB = createUser("사용자 B");
            User userC = createUser("사용자 C");
            User userD = createUser("사용자 D");
            User outsider = createUser("외부인");
            members.save(TeamMember.create(team.getId(), owner.getId(), MemberRole.OWNER));
            members.save(TeamMember.create(team.getId(), userB.getId(), MemberRole.MEMBER));
            members.save(TeamMember.create(team.getId(), userC.getId(), MemberRole.MEMBER));
            members.save(TeamMember.create(team.getId(), userD.getId(), MemberRole.MEMBER));
            members.save(TeamMember.create(otherTeam.getId(), outsider.getId(), MemberRole.OWNER));

            requester = createAgent(team, owner, "호출 Agent", AgentPart.FRONTEND, List.of("로그인", "인증"));
            agentB = createAgent(team, userB, "인증 Agent", AgentPart.BACKEND, List.of("로그인", "인증"));
            agentC = createAgent(team, userC, "토큰 Agent", AgentPart.BACKEND, List.of("로그인", "인증"));
            agentD = createAgent(team, userD, "세션 Agent", AgentPart.BACKEND, List.of("로그인", "인증"));
            otherTeamAgent = createAgent(otherTeam, outsider, "다른 팀 Agent", AgentPart.OTHER, List.of("로그인"));
        });
    }

    @Test
    void designExampleRanksCandidatesByRoleAndGitEvidence() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            repoId = repositories.save(TeamGitRepository.create(team.getId(), "a".repeat(64))).getId();
            Long unrelatedRepo = repositories.save(TeamGitRepository.create(team.getId(), "b".repeat(64))).getId();

            // B: 관련 파일 작업 중, 커밋 1건(2일 전). 같은 커밋이 파일 두 개를 바꿔도 1회다.
            Long b1 = commit(repoId, agentB.ownerUserId(), now.minus(Duration.ofDays(2)));
            file(b1, RELATED_PATH);
            file(b1, "src/auth/TokenIssuer.java");
            working(repoId, agentB.ownerUserId(), now.minusSeconds(20), List.of(RELATED_PATH, "README.md"));

            // C: 관련 파일 작업 중, 커밋 2건(최신 28일 전)
            file(commit(repoId, agentC.ownerUserId(), now.minus(Duration.ofDays(28))), RELATED_PATH);
            file(commit(repoId, agentC.ownerUserId(), now.minus(Duration.ofDays(40))), RELATED_PATH);
            working(repoId, agentC.ownerUserId(), now.minusSeconds(5), List.of(RELATED_PATH));

            // D: 작업 관측은 만료, 커밋 2건(최신 14일 전). 91일 전 커밋과 미확인 작성자 커밋은 제외된다.
            file(commit(repoId, agentD.ownerUserId(), now.minus(Duration.ofDays(14))), RELATED_PATH);
            file(commit(repoId, agentD.ownerUserId(), now.minus(Duration.ofDays(60))), RELATED_PATH);
            file(commit(repoId, agentD.ownerUserId(), now.minus(Duration.ofDays(91))), RELATED_PATH);
            file(commit(repoId, null, now.minus(Duration.ofDays(1))), RELATED_PATH);
            working(repoId, agentD.ownerUserId(), now.minus(Duration.ofMinutes(10)), List.of(RELATED_PATH));

            // 요청자 자신의 근거와 무관한 저장소의 다른 경로는 점수에 영향이 없다.
            file(commit(repoId, requester.ownerUserId(), now), RELATED_PATH);
            file(commit(unrelatedRepo, agentD.ownerUserId(), now), "src/payment/RefundService.java");
        });

        JsonNode data = assertSuccess(post(team.getId(), requester.token(), body(QUESTION, "a".repeat(64))));

        assertThat(data.path("status").asText()).isEqualTo("MULTIPLE_CANDIDATES");
        assertThat(data.path("insufficientReason").isNull()).isTrue();
        assertThat(Instant.parse(data.path("evaluatedAt").asText())).isAfterOrEqualTo(now);
        assertThat(data.path("gitCoverage").path("state").asText()).isEqualTo("AVAILABLE");
        assertThat(data.path("gitCoverage").path("reason").isNull()).isTrue();
        assertThat(data.path("gitCoverage").path("workingState").asText()).isEqualTo("FRESH");

        JsonNode candidates = data.path("candidates");
        assertThat(candidates.size()).isEqualTo(2);
        JsonNode first = candidates.get(0);
        assertThat(first.path("agentId").asLong()).isEqualTo(agentB.id());
        assertThat(first.path("name").asText()).isEqualTo("인증 Agent");
        assertThat(first.path("part").asText()).isEqualTo("BACKEND");
        assertThat(first.path("connectionStatus").asText()).isEqualTo("OFFLINE");
        assertThat(first.path("score").asDouble()).isEqualTo(67.06);
        assertThat(first.path("scores").path("role").asDouble()).isEqualTo(40.0);
        assertThat(first.path("scores").path("git").asDouble()).isEqualTo(27.06);
        assertThat(first.path("scores").path("working").asDouble()).isEqualTo(15.0);
        assertThat(first.path("scores").path("frequency").asDouble()).isEqualTo(3.0);
        assertThat(first.path("scores").path("recency").asDouble()).isEqualTo(9.06);
        assertThat(toList(first.path("matchedRoles"))).containsExactly("로그인", "인증");
        assertThat(first.path("latestCommitAt").isNull()).isFalse();
        assertThat(first.path("workingObservedAt").isNull()).isFalse();
        assertThat(first.path("insufficientReason").isNull()).isTrue();
        JsonNode relatedFiles = first.path("relatedFiles");
        assertThat(relatedFiles.size()).isEqualTo(2);
        assertThat(relatedFiles.get(0).path("repoId").asLong()).isEqualTo(repoId);
        assertThat(relatedFiles.get(0).path("relativePath").asText()).isEqualTo(RELATED_PATH);
        assertThat(toList(relatedFiles.get(0).path("sources"))).containsExactly("WORKING", "COMMIT");
        assertThat(relatedFiles.get(1).path("relativePath").asText()).isEqualTo("src/auth/TokenIssuer.java");
        assertThat(toList(relatedFiles.get(1).path("sources"))).containsExactly("COMMIT");

        JsonNode second = candidates.get(1);
        assertThat(second.path("agentId").asLong()).isEqualTo(agentC.id());
        assertThat(second.path("score").asDouble()).isEqualTo(63.5);
        assertThat(second.path("scores").path("frequency").asDouble()).isEqualTo(6.0);
        assertThat(second.path("scores").path("recency").asDouble()).isEqualTo(2.5);
    }

    @Test
    void koreanOnlyQuestionScoresRolesButNotFiles() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            repoId = repositories.save(TeamGitRepository.create(team.getId(), "c".repeat(64))).getId();
            file(commit(repoId, agentB.ownerUserId(), now), RELATED_PATH);
            agents.findById(agentC.id()).orElseThrow().updateRoutingMetadata(null, List.of("토큰"));
            agents.findById(agentD.id()).orElseThrow().updateRoutingMetadata(null, List.of());
        });

        JsonNode data = assertSuccess(post(team.getId(), requester.token(), body("리프레시 토큰을 어떻게 반환해?", null)));

        assertThat(data.path("status").asText()).isEqualTo("MATCHED");
        assertThat(data.path("gitCoverage").path("state").asText()).isEqualTo("AVAILABLE");
        assertThat(data.path("gitCoverage").path("workingState").asText()).isEqualTo("NOT_COLLECTED");
        JsonNode only = data.path("candidates").get(0);
        assertThat(data.path("candidates").size()).isEqualTo(1);
        assertThat(only.path("agentId").asLong()).isEqualTo(agentC.id());
        assertThat(only.path("score").asDouble()).isEqualTo(20.0);
        assertThat(only.path("scores").path("git").asDouble()).isZero();
        assertThat(only.path("relatedFiles").size()).isZero();
        assertThat(toList(only.path("matchedRoles"))).containsExactly("토큰");
    }

    @Test
    void noMatchingEvidenceReturnsZeroScoreReferenceCandidates() throws Exception {
        JsonNode data = assertSuccess(post(team.getId(), requester.token(), body("배포 파이프라인이 왜 느려?", null)));

        assertThat(data.path("status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(data.path("insufficientReason").asText()).isEqualTo("NO_MATCHING_EVIDENCE");
        assertThat(data.path("gitCoverage").path("state").asText()).isEqualTo("NOT_COLLECTED");
        assertThat(data.path("gitCoverage").path("reason").asText()).isEqualTo("NO_TEAM_REPOSITORIES");
        assertThat(data.path("candidates").size()).isEqualTo(3);
        for (JsonNode candidate : data.path("candidates")) {
            assertThat(candidate.path("agentId").asLong()).isNotEqualTo(requester.id());
            assertThat(candidate.path("score").asDouble()).isZero();
            assertThat(candidate.path("insufficientReason").asText()).isEqualTo("NO_MATCHING_EVIDENCE");
        }
    }

    @Test
    void agentsOfRemovedMembersAreExcludedBeforeScoring() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            agents.findById(agentC.id()).orElseThrow().updateRoutingMetadata(null, List.of());
            agents.findById(agentD.id()).orElseThrow().updateRoutingMetadata(null, List.of());
            members.findByTeamIdAndUserId(team.getId(), agentB.ownerUserId())
                    .ifPresent(member -> members.delete(member));
        });

        JsonNode data = assertSuccess(post(team.getId(), requester.token(), body(QUESTION, null)));

        // 태그가 일치하는 B는 소유자가 탈퇴했으므로 참고 후보에도 나타나지 않는다.
        assertThat(data.path("status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(data.path("insufficientReason").asText()).isEqualTo("NO_MATCHING_EVIDENCE");
        assertThat(toLongs(data.path("candidates"))).containsExactly(agentC.id(), agentD.id());
    }

    @Test
    void requesterAloneInTeamHasNoEligibleAgents() throws Exception {
        JsonNode data = assertSuccess(post(otherTeam.getId(), otherTeamAgent.token(), body(QUESTION, null)));

        assertThat(data.path("status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(data.path("insufficientReason").asText()).isEqualTo("NO_ELIGIBLE_AGENTS");
        assertThat(data.path("candidates").isArray()).isTrue();
        assertThat(data.path("candidates").size()).isZero();
    }

    @Test
    void validationAuthenticationAndTeamAccessFailures() throws Exception {
        assertError(post(team.getId(), requester.token(), body("   ", null)), 400, "VALIDATION_FAILED");
        assertError(post(team.getId(), requester.token(), body("a".repeat(1001), null)), 400, "VALIDATION_FAILED");
        assertError(post(team.getId(), requester.token(), "{\"question\": 1, \"extra\": [}"), 400, "VALIDATION_FAILED");
        assertError(post(team.getId(), requester.token(), "{\"question\": 123}"), 400, "VALIDATION_FAILED");
        assertError(post(team.getId(), requester.token(), "{\"question\": true}"), 400, "VALIDATION_FAILED");
        assertError(post(team.getId(), requester.token(), "{\"question\": \"" + QUESTION + "\", \"repoKey\": 7}"),
                400, "VALIDATION_FAILED");
        assertError(post(team.getId(), null, body(QUESTION, null)), 401, "AUTHENTICATION_REQUIRED");
        assertError(post(team.getId(), "not-a-token", body(QUESTION, null)), 401, "AUTHENTICATION_REQUIRED");
        assertError(post(otherTeam.getId(), requester.token(), body(QUESTION, null)), 403, "ACCESS_DENIED");
    }

    @Test
    void realBrokerDisconnectAndReconnectOnlyChangeConnectionStatus() throws Exception {
        JsonNode baseline = assertSuccess(post(team.getId(), requester.token(), body(QUESTION, null)));
        assertThat(baseline.path("candidates").get(0).path("connectionStatus").asText()).isEqualTo("OFFLINE");

        try (var broker = OnlineTestBroker.connect(port, agentB.token(), agentB.id(), agents)) {
            assertSameRankingWithOnlineFirstCandidate(baseline);
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(connections.findByAgentId(agentB.id().toString())).isEmpty();
            assertThat(agents.findById(agentB.id()).orElseThrow().getStatus()).isEqualTo(AgentStatus.OFFLINE);
        });
        JsonNode disconnected = assertSuccess(post(team.getId(), requester.token(), body(QUESTION, null)));
        assertThat(disconnected.path("candidates")).isEqualTo(baseline.path("candidates"));

        try (var broker = OnlineTestBroker.connect(port, agentB.token(), agentB.id(), agents)) {
            assertSameRankingWithOnlineFirstCandidate(baseline);
        }
    }

    @ParameterizedTest
    @EnumSource(AgentStatus.class)
    void recommendedDisconnectedCandidateCreatesPendingTaskWithExistingDeliveryReason(AgentStatus savedStatus)
            throws Exception {
        if (savedStatus == AgentStatus.ONLINE) {
            // DB 갱신이 늦어 ONLINE만 남았지만 실제 브로커 연결은 없는 상태를 재현한다.
            new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                    agents.findById(agentB.id()).orElseThrow().markOnline(LocalDateTime.now()));
        }
        JsonNode recommendation = assertSuccess(post(team.getId(), requester.token(), body(QUESTION, null)));
        JsonNode selected = recommendation.path("candidates").get(0);
        assertThat(selected.path("agentId").asLong()).isEqualTo(agentB.id());
        assertThat(selected.path("connectionStatus").asText()).isEqualTo("OFFLINE");
        // 추천 조회가 DB의 상태를 덮어쓰지 않아 기존 전달 사유도 구분된다.
        assertThat(agents.findById(agentB.id()).orElseThrow().getStatus()).isEqualTo(savedStatus);

        String key = UUID.randomUUID().toString();
        String request = json.writeValueAsString(json.createObjectNode()
                .put("requestKey", key).put("receiverAgentId", selected.path("agentId").asLong())
                .put("prompt", "추천된 후보에게 작업 요청"));
        HttpResponse<String> response = postPath("/api/v1/tasks/" + team.getId(), requester.token(), request);
        assertThat(response.statusCode()).as("HTTP response: %s", response.body()).isEqualTo(201);
        JsonNode envelope = json.readTree(response.body());
        assertThat(envelope.path("success").asBoolean()).isTrue();
        JsonNode created = envelope.path("data");
        assertThat(created.path("status").asText()).isEqualTo("PENDING");
        assertThat(created.path("delivery").path("status").asText()).isEqualTo("DELIVERY_NOT_SENT");
        assertThat(created.path("delivery").path("reasonCode").asText()).isEqualTo(
                savedStatus == AgentStatus.OFFLINE ? "RECEIVER_OFFLINE" : "CONNECTION_UNAVAILABLE");
        long taskId = created.path("taskId").asLong();
        assertThat(tasks.findById(taskId).orElseThrow().getDeliveredAt()).isNull();
        assertThat(taskEvents.findByTaskIdOrderBySequenceAsc(taskId)).extracting(TaskEvent::getEventType)
                .containsExactly(TaskEventType.CREATED, TaskEventType.DELIVERY_NOT_SENT);

        // 같은 수신자가 다시 ONLINE을 보고하면 새 작업 생성 없이 기존 미전달 작업을 전송한다.
        try (var broker = OnlineTestBroker.connect(port, agentB.token(), agentB.id(), agents)) {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(taskEvents.findByTaskIdOrderBySequenceAsc(taskId)).extracting(TaskEvent::getEventType)
                            .containsExactly(TaskEventType.CREATED, TaskEventType.DELIVERY_NOT_SENT,
                                    TaskEventType.ACK_PENDING));
            assertThat(tasks.findByRequesterAgentIdAndRequestKey(requester.id(), key).orElseThrow().getId())
                    .isEqualTo(taskId);
            assertThat(tasks.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.PENDING);
            assertThat(tasks.findById(taskId).orElseThrow().getDeliveredAt()).isNull();
        }
    }

    private void assertSameRankingWithOnlineFirstCandidate(JsonNode baseline) throws Exception {
        JsonNode connected = assertSuccess(post(team.getId(), requester.token(), body(QUESTION, null)));
        assertThat(connected.path("status")).isEqualTo(baseline.path("status"));
        assertThat(connected.path("candidates").get(0).path("connectionStatus").asText()).isEqualTo("ONLINE");
        JsonNode expected = baseline.deepCopy();
        ((ObjectNode) expected.path("candidates").get(0))
                .put("connectionStatus", "ONLINE");
        assertThat(connected.path("candidates")).isEqualTo(expected.path("candidates"));
    }

    @Test
    void importExpansionUsesCurrentSnapshotAndWeightsIndirectEvidence() throws Exception {
        String importer = "src/web/CheckoutController.java";
        String sha = "a".repeat(40);
        Long repo = new TransactionTemplate(transactionManager).execute(tx -> {
            Long id = repositories.saveAndFlush(TeamGitRepository.create(team.getId(), "b".repeat(64))).getId();
            file(commit(id, requester.ownerUserId(), now.minusSeconds(1)), RELATED_PATH);
            file(commit(id, agentB.ownerUserId(), now.minusSeconds(1)), importer);
            working(id, agentB.ownerUserId(), now, List.of(importer));
            jdbc.update("""
                    insert into repository_dependency_graphs
                        (repo_id, display_name, branch_name, repo_kind, analysis_commit_sha,
                         analysis_version, analyzed_at, graph)
                    values (?, 'test', 'main', 'BACKEND', ?, 1, now(), '{"nodes":[],"edges":[]}'::jsonb)
                    """, id, sha);
            for (String relation : List.of("IMPORT", "CALLS")) {
                jdbc.update("""
                        insert into file_dependencies
                            (repo_id, source_relative_path, target_relative_path,
                             relation_type, analysis_commit_sha, observed_at)
                        values (?, ?, ?, ?, ?, now())
                        """, id, relation.equals("IMPORT") ? importer : "ignored.java", RELATED_PATH, relation, sha);
            }
            jdbc.update("""
                    insert into file_dependencies
                        (repo_id, source_relative_path, target_relative_path,
                         relation_type, analysis_commit_sha, observed_at)
                    values (?, 'old.java', ?, 'IMPORT', ?, now())
                    """, id, RELATED_PATH, "c".repeat(40));
            return id;
        });
        assertThat(dependencies.findImportingFiles(team.getId(), repo, List.of(RELATED_PATH)))
                .extracting(ImportingFileView::getSourcePath)
                .containsExactly(importer);
        assertThat(dependencies.findImportingFiles(otherTeam.getId(), repo, List.of(RELATED_PATH))).isEmpty();

        JsonNode response = assertSuccess(post(team.getId(), requester.token(), body("refreshToken", null)));
        JsonNode candidate = response.path("candidates").get(0);
        assertThat(candidate.path("agentId").asLong()).isEqualTo(agentB.id());
        assertThat(candidate.path("scores").path("working").asDouble()).isEqualTo(7.5);
        assertThat(candidate.path("scores").path("frequency").asDouble()).isEqualTo(1.5);
        assertThat(candidate.path("scores").path("recency").asDouble())
                .isCloseTo(5.0, within(0.01));
        JsonNode file = candidate.path("relatedFiles").get(0);
        assertThat(file.path("relativePath").asText()).isEqualTo(importer);
        List<String> fields = new ArrayList<>();
        file.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("repoId", "relativePath", "sources", "observedAt");
    }

    private HttpResponse<String> post(Long teamId, String token, String body) throws Exception {
        return postPath("/api/v1/teams/" + teamId + "/agents/recommendations", token, body);
    }

    private HttpResponse<String> postPath(String path, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private String body(String question, String repoKey) throws Exception {
        var node = json.createObjectNode().put("question", question);
        if (repoKey != null) {
            node.put("repoKey", repoKey);
        }
        return json.writeValueAsString(node);
    }

    private JsonNode assertSuccess(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as("HTTP response: %s", response.body()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("code").isNull()).isTrue();
        assertThat(body.path("message").isNull()).isTrue();
        assertThat(body.path("data").isObject()).isTrue();
        return body.path("data");
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as("HTTP response: %s", response.body()).isEqualTo(status);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("data").isNull()).isTrue();
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("message").asText()).isNotBlank();
    }

    private static List<Long> toLongs(JsonNode candidates) {
        List<Long> ids = new ArrayList<>();
        candidates.forEach(node -> ids.add(node.path("agentId").asLong()));
        return ids;
    }

    private static List<String> toList(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private User createUser(String name) {
        return users.save(User.create(UUID.randomUUID() + "@example.com", name, null, "password-hash"));
    }

    private TestAgent createAgent(Team targetTeam, User user, String name, AgentPart part, List<String> roles) {
        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Agent agent = Agent.create(targetTeam.getId(), user.getId(), name, null, ToolType.CODEX, "recommendation-test",
                part, roles);
        agent.issueConnectionToken(AgentTokenHash.sha256(token));
        agent = agents.saveAndFlush(agent);
        return new TestAgent(agent.getId(), user.getId(), token);
    }

    private Long commit(Long repo, Long authorUserId, Instant committedAt) {
        String sha = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 8);
        return commits.save(RepositoryCommit.of(team.getId(), repo, sha, committedAt, authorUserId)).getId();
    }

    private void file(Long commitId, String relativePath) {
        commitFiles.save(CommitFile.of(commitId, relativePath, CommitChangeType.MODIFIED));
    }

    private void working(Long repo, Long userId, Instant observedAt, List<String> paths) {
        List<WorkingFileEntry> entries = paths.stream()
                .map(path -> new WorkingFileEntry(path, WorkingFileChangeType.MODIFIED))
                .toList();
        workingFiles.save(WorkingFile.observe(repo, userId, "feature/auth", entries, observedAt,
                observedAt.plusSeconds(90)));
    }

    private record TestAgent(Long id, Long ownerUserId, String token) {
    }
}
