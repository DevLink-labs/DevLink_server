package com.devlink.server.service;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.devlink.server.client.RoleDecisionClient;
import com.devlink.server.config.DecisionModelSettings;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.AgentPart;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.dto.recommendation.AgentRecommendationRequest;
import com.devlink.server.dto.recommendation.AgentRecommendationResponse;
import com.devlink.server.dto.recommendation.GitCoverageState;
import com.devlink.server.dto.recommendation.RecommendationCandidateResponse;
import com.devlink.server.dto.recommendation.RecommendationStatus;
import com.devlink.server.dto.recommendation.WorkingObservationState;
import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.repository.AgentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 같은 팀 구성과 질문(recommendation/role-decision-team.txt)으로 결정 모델을 끈 추천과 켠 추천을 비교한다.
 * 추천 서비스는 운영 코드 그대로이고, DB 조회와 Git 근거는 대체해 역할 점수만 본다. 그래서 지연은 추천 전체가 아니라
 * 결정 모델 호출이 더하는 시간을 보여 준다.
 * 실제 모델 측정은 기본 테스트 실행에서 건너뛴다. 실행하려면 환경 변수 DECISION_MODEL_EVAL=true와 DECISION_MODEL_API_KEY를 두고
 * {@code ./gradlew test --tests '*RoleDecisionRecommendationBenchmarkTest' -i}를 실행한다.
 * 제한 시간은 운영 기본값(1500ms)을 쓰며 DECISION_MODEL_TIMEOUT_MS로 바꾼다. 반복 횟수는 DECISION_MODEL_BENCH_ROUNDS(기본 3)다.
 * 호출은 하나씩 차례로 보낸다.
 */
class RoleDecisionRecommendationBenchmarkTest {
    private static final String RESOURCE = "/recommendation/role-decision-team.txt";
    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");
    private static final long TEAM = 7L;
    private static final AgentPrincipal REQUESTER = new AgentPrincipal(10L, 100L, TEAM);
    private static final String CATCH_ALL_KEY = "any";

    private final AgentRepository agents = mock(AgentRepository.class);
    private final TeamAuthorizationService teams = mock(TeamAuthorizationService.class);
    private final RecommendationGitEvidenceService evidence = mock(RecommendationGitEvidenceService.class);
    private final AgentPresenceService presence = new AgentPresenceService(
            agents, mock(ConnectionRegistry.class), mock(ApplicationEventPublisher.class), 40000);

    @Test
    void teamFixtureIsWellFormedAndTheDisabledModelNeverPicksANonOwner() throws IOException {
        Fixture fixture = load();
        stubTeam(fixture);

        assertThat(fixture.agents()).hasSizeGreaterThanOrEqualTo(5);
        assertThat(fixture.questions()).hasSizeGreaterThanOrEqualTo(30);
        assertThat(fixture.questions().stream().map(Question::text)).doesNotHaveDuplicates();
        assertThat(fixture.questions()).allSatisfy(question ->
                assertThat(fixture.agents().keySet()).as(question.text()).containsAll(question.owners()));
        assertThat(fixture.questions()).anyMatch(question -> question.owners().isEmpty());

        // 끈 상태는 토큰 일치만 쓴다. 이 세트에서 토큰 일치는 틀린 담당을 고르지 않고, 무엇에나 맞는 태그를 단 Agent도 고르지 않는다.
        // 토큰으로 담당을 찾는 질문과 찾지 못하는 질문이 모두 있어야 켠 상태와 비교할 수 있다.
        AgentRecommendationService disabled = service(RoleDecisionClient.disabled());
        int found = 0;
        int ownerNotFound = 0;
        for (Question question : fixture.questions()) {
            Outcome outcome = recommend(disabled, fixture, question);
            if (outcome.status() != RecommendationStatus.INSUFFICIENT_EVIDENCE) {
                assertThat(question.owners()).as(question.text()).containsAll(outcome.selected());
                found++;
            } else if (!question.owners().isEmpty()) {
                ownerNotFound++;
            }
        }
        assertThat(found).isPositive();
        assertThat(ownerNotFound).isPositive();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DECISION_MODEL_EVAL", matches = "true")
    void reportsRecommendationsWithTheModelOffAndOn() throws IOException {
        Fixture fixture = load();
        stubTeam(fixture);
        long timeoutMs = Long.parseLong(RoleDecisionBenchmarkSupport.env("DECISION_MODEL_TIMEOUT_MS", "1500"));
        int rounds = Integer.parseInt(RoleDecisionBenchmarkSupport.env("DECISION_MODEL_BENCH_ROUNDS", "3"));
        DecisionModelSettings settings = RoleDecisionBenchmarkSupport.settingsFromEnvironment(timeoutMs);
        RoleDecisionBenchmarkSupport.Usage usage = new RoleDecisionBenchmarkSupport.Usage();
        CountingClient model = new CountingClient(RoleDecisionBenchmarkSupport.client(settings, usage));
        AgentRecommendationService disabled = service(RoleDecisionClient.disabled());
        AgentRecommendationService enabled = service(model);

        Tally off = new Tally();
        List<Outcome> offOutcomes = new ArrayList<>();
        for (Question question : fixture.questions()) {
            Outcome outcome = recommend(disabled, fixture, question);
            offOutcomes.add(outcome);
            off.add(question, outcome);
        }
        List<Tally> on = new ArrayList<>();
        List<List<Outcome>> onOutcomes = new ArrayList<>();
        for (int round = 0; round < rounds; round++) {
            Tally tally = new Tally();
            List<Outcome> outcomes = new ArrayList<>();
            for (Question question : fixture.questions()) {
                Outcome outcome = recommend(enabled, fixture, question);
                outcomes.add(outcome);
                tally.add(question, outcome);
            }
            on.add(tally);
            onOutcomes.add(outcomes);
        }

        StringBuilder report = new StringBuilder("\n== 추천 결과 비교 (" + settings.model() + ", 임계값 "
                + settings.threshold() + ", 제한 시간 " + timeoutMs + "ms) ==\n");
        long withOwner = fixture.questions().stream().filter(question -> !question.owners().isEmpty()).count();
        report.append("팀 Agent %d명, 질문 %d건(담당 있음 %d, 담당 없음 %d), 켠 상태 %d회 반복%n".formatted(fixture.agents().size(),
                fixture.questions().size(), withOwner, fixture.questions().size() - withOwner, rounds));
        report.append("도입 전: %s%n".formatted(off));
        for (int round = 0; round < rounds; round++) {
            report.append("도입 후 %d회차: %s%n".formatted(round + 1, on.get(round)));
        }
        List<Long> offLatency = offOutcomes.stream().map(Outcome::millis).toList();
        List<Long> onLatency = onOutcomes.stream().flatMap(List::stream).map(Outcome::millis).toList();
        report.append("추천 한 번의 지연(ms), 도입 전: %s%n".formatted(RoleDecisionBenchmarkSupport.latency(offLatency)));
        report.append("추천 한 번의 지연(ms), 도입 후: %s%n".formatted(RoleDecisionBenchmarkSupport.latency(onLatency)));
        report.append("모델 호출 %d회, 2xx 응답을 받지 못한 호출 %d회(이때는 도입 전과 같은 결과), 호출당 물은 태그 평균 %.1f개%n"
                .formatted(model.calls, model.calls - usage.responses(), model.calls == 0 ? 0 : (double) model.tags / model.calls));
        report.append("사용량: %s%n".formatted(usage.summary()));
        report.append("질문별(기대 담당 | 도입 전 | 도입 후 회차별):%n".formatted());
        for (int index = 0; index < fixture.questions().size(); index++) {
            Question question = fixture.questions().get(index);
            List<String> after = new ArrayList<>();
            for (List<Outcome> outcomes : onOutcomes) {
                after.add(outcomes.get(index).describe());
            }
            report.append("  %s | %s | %s | %s%n".formatted(question.owners().isEmpty() ? "-" : String.join(",", question.owners()),
                    offOutcomes.get(index).describe(), String.join(" / ", after), question.text()));
        }
        System.out.println(report);
        assertThat(model.calls).isPositive();
    }

    private Outcome recommend(AgentRecommendationService service, Fixture fixture, Question question) {
        long started = System.nanoTime();
        AgentRecommendationResponse response = service.recommend(TEAM, REQUESTER,
                new AgentRecommendationRequest(question.text(), null));
        long millis = (System.nanoTime() - started) / 1_000_000;
        List<RecommendationCandidateResponse> candidates = response.status() == RecommendationStatus.INSUFFICIENT_EVIDENCE
                ? List.of() : response.candidates();
        List<String> selected = candidates.stream().map(RecommendationCandidateResponse::agentId).map(fixture::keyOf).toList();
        List<String> detail = candidates.stream()
                .map(candidate -> fixture.keyOf(candidate.agentId()) + "(" + String.join("·", candidate.matchedRoles()) + ")")
                .toList();
        return new Outcome(response.status(), selected, detail, millis);
    }

    private AgentRecommendationService service(RoleDecisionClient client) {
        return new AgentRecommendationService(agents, teams, evidence, presence, fixedClock(), client,
                TransactionOperations.withoutTransaction());
    }

    private void stubTeam(Fixture fixture) {
        when(agents.findRecommendationCandidates(TEAM)).thenReturn(List.copyOf(fixture.agents().values()));
        when(evidence.collect(eq(TEAM), any(), anyList(), any())).thenReturn(
                RecommendationGitEvidence.empty(GitCoverageState.AVAILABLE, null, WorkingObservationState.STALE));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<Clock> fixedClock() {
        ObjectProvider<Clock> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
        return provider;
    }

    private static Fixture load() throws IOException {
        Map<String, Agent> agents = new LinkedHashMap<>();
        List<Question> questions = new ArrayList<>();
        long nextId = 21;
        for (String line : RoleDecisionBenchmarkSupport.resourceLines(RESOURCE)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\\|");
            if (fields[0].equals("agent")) {
                assertThat(fields).as(line).hasSize(4);
                Agent agent = Agent.create(TEAM, 200 + nextId, fields[2].strip(), null, ToolType.CODEX, "project",
                        AgentPart.BACKEND, List.of(fields[3].split(",")));
                ReflectionTestUtils.setField(agent, "id", nextId++);
                assertThat(agents.put(fields[1].strip(), agent)).as(line).isNull();
            } else {
                assertThat(fields).as(line).hasSize(3);
                assertThat(fields[0]).as(line).isEqualTo("q");
                Set<String> owners = fields[1].strip().equals("-") ? Set.of()
                        : new LinkedHashSet<>(List.of(fields[1].strip().split(",")));
                questions.add(new Question(owners, fields[2].strip()));
            }
        }
        return new Fixture(agents, questions);
    }

    private record Fixture(Map<String, Agent> agents, List<Question> questions) {
        String keyOf(Long agentId) {
            return agents.entrySet().stream().filter(entry -> entry.getValue().getId().equals(agentId))
                    .map(Map.Entry::getKey).findFirst().orElseThrow();
        }
    }

    private record Question(Set<String> owners, String text) {
    }

    /** 추천 상태와 선정된 Agent 키(순위 순), 키별 일치 태그. 근거 부족이면 선정된 Agent가 없다. */
    private record Outcome(RecommendationStatus status, List<String> selected, List<String> detail, long millis) {
        String describe() {
            return switch (status) {
                case INSUFFICIENT_EVIDENCE -> "근거 부족";
                case MATCHED -> "단독 " + detail.getFirst();
                case MULTIPLE_CANDIDATES -> "복수 " + String.join(",", detail);
            };
        }
    }

    /** 담당이 있는 질문과 없는 질문을 나눠 센다. */
    private static final class Tally {
        private final Map<RecommendationStatus, Integer> statuses = new EnumMap<>(RecommendationStatus.class);
        private int ownerQuestions;
        private int soleOwner;
        private int ownerAmongSeveral;
        private int wrongAgentOnly;
        private int ownerNotFound;
        private int noOwnerQuestions;
        private int noOwnerRejected;
        private int catchAllSelected;

        void add(Question question, Outcome outcome) {
            statuses.merge(outcome.status(), 1, Integer::sum);
            if (outcome.selected().contains(CATCH_ALL_KEY)) {
                catchAllSelected++;
            }
            if (question.owners().isEmpty()) {
                noOwnerQuestions++;
                if (outcome.status() == RecommendationStatus.INSUFFICIENT_EVIDENCE) {
                    noOwnerRejected++;
                }
                return;
            }
            ownerQuestions++;
            boolean ownerSelected = outcome.selected().stream().anyMatch(question.owners()::contains);
            boolean onlyOwners = question.owners().containsAll(outcome.selected());
            if (outcome.status() == RecommendationStatus.INSUFFICIENT_EVIDENCE) {
                ownerNotFound++;
            } else if (ownerSelected && onlyOwners) {
                soleOwner++;
            } else if (ownerSelected) {
                ownerAmongSeveral++;
            } else {
                wrongAgentOnly++;
            }
        }

        @Override
        public String toString() {
            return ("담당 있는 질문 %d건 중 담당만 추천 %d, 담당과 다른 Agent를 함께 추천 %d, 다른 Agent만 추천 %d, 근거 부족 %d"
                    + " | 담당 없는 질문 %d건 중 근거 부족 %d, 잘못 추천 %d | 만능 Agent 추천 %d회 | 상태 %s")
                    .formatted(ownerQuestions, soleOwner, ownerAmongSeveral, wrongAgentOnly, ownerNotFound,
                            noOwnerQuestions, noOwnerRejected, noOwnerQuestions - noOwnerRejected, catchAllSelected, statuses);
        }
    }

    /** 모델 호출 횟수와 물은 태그 수를 센다. */
    private static final class CountingClient implements RoleDecisionClient {
        private final RoleDecisionClient delegate;
        private int calls;
        private int tags;

        private CountingClient(RoleDecisionClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public Set<String> matchedTags(String question, List<String> asked) {
            calls++;
            tags += asked.size();
            return delegate.matchedTags(question, asked);
        }
    }
}
