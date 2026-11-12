package com.devlink.server.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;
import com.devlink.server.client.GeminiRoleDecisionClient;
import com.devlink.server.client.RoleDecisionClient;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.config.DecisionModelSettings;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.AgentPart;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.dto.recommendation.AgentRecommendationRequest;
import com.devlink.server.dto.recommendation.AgentRecommendationResponse;
import com.devlink.server.dto.recommendation.GitCoverageState;
import com.devlink.server.dto.recommendation.InsufficientReason;
import com.devlink.server.dto.recommendation.RecommendationCandidateResponse;
import com.devlink.server.dto.recommendation.RecommendationStatus;
import com.devlink.server.dto.recommendation.WorkingObservationState;
import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.repository.AgentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 결정 모델을 켠 추천의 역할 점수 보강과, 모델 실패 시 기능을 끈 경우와 같은 응답을 검증한다. */
class AgentRecommendationRoleDecisionTest {
    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");
    private static final long TEAM = 7L;
    private static final AgentPrincipal REQUESTER = new AgentPrincipal(10L, 100L, TEAM);
    private static final String QUESTION = "로그인 세션이 자꾸 풀려요";
    private static final String URL = "https://generativelanguage.googleapis.com";

    private final AgentRepository agents = mock(AgentRepository.class);
    private final TeamAuthorizationService teams = mock(TeamAuthorizationService.class);
    private final RecommendationGitEvidenceService evidence = mock(RecommendationGitEvidenceService.class);
    private final AgentPresenceService presence = new AgentPresenceService(
            agents, mock(ConnectionRegistry.class), mock(ApplicationEventPublisher.class), 40000);
    private final RecordingClient model = new RecordingClient();

    @Test
    void modelMatchRaisesRoleScoreAndOnlyTokenUnmatchedTagsAreAsked() {
        stubTeam(List.of(
                agent(10L, 100L, "Me", List.of("요청자 전용 태그")),
                agent(21L, 200L, "인증 Agent", List.of("로그인", "authentication")),
                agent(23L, 300L, "결제 Agent", List.of("결제", "authentication"))));
        model.matched = Set.of("authentication");

        AgentRecommendationResponse response = service(model).recommend(TEAM, REQUESTER,
                new AgentRecommendationRequest("  " + QUESTION + " ", null));

        // 요청자 자신의 태그와 토큰으로 이미 일치한 "로그인"은 보내지 않고, 같은 태그는 한 번만 묻는다.
        assertThat(model.questions).containsExactly(QUESTION);
        assertThat(model.tags).containsExactly(List.of("authentication", "결제"));
        assertThat(response.status()).isEqualTo(RecommendationStatus.MATCHED);
        RecommendationCandidateResponse selected = response.candidates().getFirst();
        assertThat(selected.agentId()).isEqualTo(21L);
        assertThat(selected.matchedRoles()).containsExactly("로그인", "authentication");
        assertThat(selected.scores().role()).isEqualTo(40.0);
        assertThat(selected.score()).isEqualTo(40.0);
    }

    @Test
    void modelOnlyMatchTurnsInsufficientEvidenceIntoMatch() {
        stubTeam(List.of(agent(21L, 200L, "인증 Agent", List.of("로그인"))));
        AgentRecommendationRequest request = new AgentRecommendationRequest("sign-in 버튼이 안 눌려요", null);

        AgentRecommendationResponse disabled = service(RoleDecisionClient.disabled()).recommend(TEAM, REQUESTER, request);
        model.matched = Set.of("로그인");
        AgentRecommendationResponse enabled = service(model).recommend(TEAM, REQUESTER, request);

        assertThat(disabled.status()).isEqualTo(RecommendationStatus.INSUFFICIENT_EVIDENCE);
        assertThat(disabled.insufficientReason()).isEqualTo(InsufficientReason.NO_MATCHING_EVIDENCE);
        assertThat(enabled.status()).isEqualTo(RecommendationStatus.MATCHED);
        assertThat(enabled.insufficientReason()).isNull();
        assertThat(enabled.candidates().getFirst().matchedRoles()).containsExactly("로그인");
        assertThat(enabled.candidates().getFirst().scores().role()).isEqualTo(20.0);
    }

    @Test
    void failedModelCallGivesTheSameResponseAsTheDisabledFeature() {
        stubTeam(List.of(
                agent(21L, 200L, "인증 Agent", List.of("로그인", "authentication")),
                agent(23L, 300L, "결제 Agent", List.of("결제"))));
        AgentRecommendationRequest request = new AgentRecommendationRequest(QUESTION, null);
        RestClient.Builder builder = RestClient.builder().baseUrl(URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL + "/v1beta/decisions")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(URL + "/v1beta/decisions"))
                .andRespond(withSuccess("{\"answers\":{}}", MediaType.APPLICATION_JSON));
        RoleDecisionClient failing = new GeminiRoleDecisionClient(builder.build(), new ObjectMapper(),
                new DecisionModelSettings(true, URL, "synthetic-decision-key-never-print", "gemini-decision-1", 1500, 0.7));

        AgentRecommendationResponse disabled = service(RoleDecisionClient.disabled()).recommend(TEAM, REQUESTER, request);

        assertThat(service(failing).recommend(TEAM, REQUESTER, request)).isEqualTo(disabled);
        assertThat(service(failing).recommend(TEAM, REQUESTER, request)).isEqualTo(disabled);
        server.verify();
        assertThat(disabled.candidates().getFirst().matchedRoles()).containsExactly("로그인");
    }

    @Test
    void nothingIsSentWhenNoTagIsLeftToAsk() {
        stubTeam(List.of(
                agent(21L, 200L, "인증 Agent", List.of("로그인")),
                agent(22L, 201L, "태그 없는 Agent", List.of())));

        AgentRecommendationResponse response = service(model).recommend(TEAM, REQUESTER,
                new AgentRecommendationRequest(QUESTION, null));

        assertThat(model.questions).isEmpty();
        assertThat(response.status()).isEqualTo(RecommendationStatus.MATCHED);
    }

    @Test
    void nonMemberIsRejectedBeforeCandidatesAreReadOrAnythingIsSent() {
        doThrow(new BusinessException(ErrorCode.ACCESS_DENIED)).when(teams).requireTeamMember(100L, TEAM);

        assertThatThrownBy(() -> service(model).recommend(TEAM, REQUESTER, new AgentRecommendationRequest(QUESTION, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
        assertThat(model.questions).isEmpty();
        verifyNoInteractions(agents, evidence);
    }

    @Test
    void onlyExplicitTagsAreSentAndTheModelAddsAtMostOneTagPerAgent() {
        Agent described = Agent.create(TEAM, 201L, "설명만 있는 Agent", "authentication, 담당자 hong@example.com",
                ToolType.CODEX, "project", AgentPart.BACKEND, List.of());
        ReflectionTestUtils.setField(described, "id", 22L);
        stubTeam(List.of(
                agent(21L, 200L, "넓은 태그 Agent", List.of("모든 개발 질문", "general engineering", "authentication")),
                described));
        model.matched = Set.of("모든 개발 질문", "general engineering", "authentication");

        AgentRecommendationResponse response = service(model).recommend(TEAM, REQUESTER,
                new AgentRecommendationRequest(QUESTION, null));

        // 역할 설명을 나눈 조각은 보내지 않고, 모델이 모든 태그에 예라고 해도 Agent당 한 태그(20점)만 더한다.
        assertThat(model.tags).containsExactly(List.of("모든 개발 질문", "general engineering", "authentication"));
        assertThat(response.status()).isEqualTo(RecommendationStatus.MATCHED);
        RecommendationCandidateResponse selected = response.candidates().getFirst();
        assertThat(selected.agentId()).isEqualTo(21L);
        assertThat(selected.matchedRoles()).containsExactly("모든 개발 질문");
        assertThat(selected.scores().role()).isEqualTo(20.0);
    }

    @Test
    void modelIsCalledBetweenTwoReadOnlyTransactionsAfterTheMembershipCheck() {
        stubTeam(List.of(agent(21L, 200L, "인증 Agent", List.of("authentication"))));
        RecordingTransactionManager transactions = new RecordingTransactionManager();
        model.matched = Set.of("authentication");
        model.openTransactions = () -> transactions.open;
        AgentRecommendationService service = new AgentRecommendationService(agents, teams, evidence, presence,
                fixedClock(), model, transactions);

        AgentRecommendationResponse response = service.recommend(TEAM, REQUESTER,
                new AgentRecommendationRequest(QUESTION, null));

        assertThat(response.candidates().getFirst().matchedRoles()).containsExactly("authentication");
        // 외부 호출 시점에 열린 트랜잭션이 없고, 앞뒤 두 트랜잭션은 모두 읽기 전용으로 커밋된다.
        assertThat(model.openTransactionsAtCall).containsExactly(0);
        assertThat(transactions.readOnly).containsExactly(true, true);
        assertThat(transactions.committed).isEqualTo(2);
        assertThat(transactions.open).isZero();
        assertThat(transactions.openAtDataAccess).containsOnly(1);
        InOrder order = inOrder(teams, agents);
        order.verify(teams).requireTeamMember(100L, TEAM);
        order.verify(agents).findRecommendationCandidates(TEAM);
        order.verify(teams).requireTeamMember(100L, TEAM);
        order.verify(agents).findRecommendationCandidates(TEAM);

        // 기능이 꺼져 있으면 트랜잭션은 기존처럼 한 번이다.
        RecordingTransactionManager single = new RecordingTransactionManager();
        new AgentRecommendationService(agents, teams, evidence, presence, fixedClock(),
                RoleDecisionClient.disabled(), single)
                .recommend(TEAM, REQUESTER, new AgentRecommendationRequest(QUESTION, null));
        assertThat(single.readOnly).containsExactly(true);
        assertThat(single.committed).isEqualTo(1);
    }

    /** 열린 트랜잭션 수와 읽기 전용 여부를 기록한다. 후보 조회 시점의 열린 수도 함께 남긴다. */
    private final class RecordingTransactionManager implements PlatformTransactionManager {
        private final List<Boolean> readOnly = new ArrayList<>();
        private final List<Integer> openAtDataAccess = new ArrayList<>();
        private int open;
        private int committed;

        private RecordingTransactionManager() {
            when(agents.findRecommendationCandidates(TEAM)).thenAnswer(invocation -> {
                openAtDataAccess.add(open);
                return List.of(agent(21L, 200L, "인증 Agent", List.of("authentication")));
            });
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            open++;
            readOnly.add(definition != null && definition.isReadOnly());
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            open--;
            committed++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            open--;
        }
    }

    private AgentRecommendationService service(RoleDecisionClient client) {
        return new AgentRecommendationService(agents, teams, evidence, presence, fixedClock(), client,
                TransactionOperations.withoutTransaction());
    }

    private void stubTeam(List<Agent> teamAgents) {
        when(agents.findRecommendationCandidates(TEAM)).thenReturn(teamAgents);
        when(evidence.collect(eq(TEAM), any(), anyList(), any())).thenReturn(
                RecommendationGitEvidence.empty(GitCoverageState.AVAILABLE, null, WorkingObservationState.STALE));
    }

    private static Agent agent(long id, long ownerUserId, String name, List<String> roles) {
        Agent agent = Agent.create(TEAM, ownerUserId, name, null, ToolType.CODEX, "project", AgentPart.BACKEND, roles);
        ReflectionTestUtils.setField(agent, "id", id);
        return agent;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<Clock> fixedClock() {
        ObjectProvider<Clock> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
        return provider;
    }

    /** 받은 질문과 태그를 기록하고 정해 둔 태그만 일치로 돌려준다. */
    private static final class RecordingClient implements RoleDecisionClient {
        private final List<String> questions = new ArrayList<>();
        private final List<List<String>> tags = new ArrayList<>();
        private final List<Integer> openTransactionsAtCall = new ArrayList<>();
        private Set<String> matched = Set.of();
        private IntSupplier openTransactions = () -> 0;

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public Set<String> matchedTags(String question, List<String> asked) {
            openTransactionsAtCall.add(openTransactions.getAsInt());
            questions.add(question);
            tags.add(asked);
            return matched;
        }
    }
}
