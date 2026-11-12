package com.devlink.server.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.devlink.server.config.TeamContextInferenceConfig;
import com.devlink.server.config.TeamContextSettings;
import com.devlink.server.domain.agent.AgentPart;
import com.devlink.server.dto.teamcontext.TeamContextInferenceRequest;
import com.devlink.server.dto.teamcontext.TeamContextInferenceRequest.Hints;
import com.devlink.server.dto.teamcontext.TeamContextInferenceRequest.NewEvent;
import com.devlink.server.dto.teamcontext.TeamContextInferenceRequest.QuestionRef;
import com.devlink.server.dto.teamcontext.TeamContextSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 추론 엔드포인트로 팀·파트 Context 증분 요약을 한 번 돌려 본다. 기본 테스트 실행에서는 건너뛴다.
 * 실행하려면 환경 변수 TEAM_CONTEXT_LIVE=true와 키(OPENROUTER_API_KEY)를 두고
 * {@code ./gradlew test --tests '*TeamContextInferenceLiveTest' -i}를 실행한다. 주소·모델·제공자 필터는 운영과 같은
 * TEAM_CONTEXT_INFERENCE_BASE_URL, TEAM_CONTEXT_INFERENCE_MODEL, TEAM_CONTEXT_INFERENCE_OPENROUTER_GUARD로 바꾼다.
 * 키 없는 자체 호스팅 서버(vLLM 등)는 주소·모델과 TEAM_CONTEXT_INFERENCE_REQUIRE_API_KEY=false를 두고 키 없이 돌린다.
 * 제한 시간과 출력 한도도 운영과 같은 TEAM_CONTEXT_INFERENCE_TIMEOUT_MS, TEAM_CONTEXT_INFERENCE_MAX_OUTPUT_TOKENS를 따른다.
 * 여섯 이벤트를 차례로 넣으므로 유료 호출이 여섯 번 생긴다. 스키마와 근거 좌표는 검사하고, 요약 품질(합의 사항 유지,
 * 미해결 쟁점 포착)은 실패로 만들지 않고 출력한다. 모델 비교는 여러 번 돌려서 한다.
 */
class TeamContextInferenceLiveTest {
    private static final long TEAM = 1L;

    private record Step(NewEvent event, Hints hints) { }

    @Test
    @EnabledIfEnvironmentVariable(named = "TEAM_CONTEXT_LIVE", matches = "true")
    void incrementalSummaryKeepsTheSchemaAndOnlyCitesGivenEvents() throws Exception {
        boolean requireApiKey = !"false".equalsIgnoreCase(env("TEAM_CONTEXT_INFERENCE_REQUIRE_API_KEY", "true"));
        String apiKey = env("OPENROUTER_API_KEY", "");
        if (requireApiKey) {
            assertThat(apiKey).as("OPENROUTER_API_KEY").isNotBlank();
        }
        // 서버가 설정을 읽는 생성자를 그대로 써서 제공자 필터(auto·true·false)와 키·주소 짝 확인이 운영과 같게 한다.
        TeamContextSettings settings = new TeamContextSettings(true,
                env("TEAM_CONTEXT_INFERENCE_BASE_URL", "https://openrouter.ai/api/v1"), apiKey, requireApiKey,
                env("TEAM_CONTEXT_INFERENCE_MODEL", TeamContextSettings.DEFAULT_OPENROUTER_MODEL),
                Long.parseLong(env("TEAM_CONTEXT_INFERENCE_TIMEOUT_MS", "60000")), 6_000, 20,
                Integer.parseInt(env("TEAM_CONTEXT_INFERENCE_MAX_OUTPUT_TOKENS", "6000")), "v1",
                env("TEAM_CONTEXT_INFERENCE_OPENROUTER_GUARD", "auto"));
        ObjectMapper mapper = new ObjectMapper();
        TeamContextInferenceClient client = new TeamContextInferenceConfig()
                .teamContextInferenceClient(settings, RestClient.builder(), mapper);
        assertThat(client).as("키가 없거나 주소와 짝이 맞지 않으면 LLM 클라이언트가 만들어지지 않는다")
                .isInstanceOf(OpenAiCompatibleTeamContextInferenceClient.class);

        TeamContextSummary summary = TeamContextSummary.empty();
        Set<String> references = new HashSet<>();
        List<Long> latenciesMs = new ArrayList<>();
        StringBuilder report = new StringBuilder("\n== 팀·파트 Context 증분 요약 (" + settings.model() + ") ==\n");
        long version = 0;
        for (Step step : steps()) {
            references.add(step.event().reference());
            long started = System.nanoTime();
            summary = client.infer(new TeamContextInferenceRequest(TEAM, AgentPart.BACKEND, AgentPart.FRONTEND, version++,
                    summary, step.event(), step.hints()));
            latenciesMs.add((System.nanoTime() - started) / 1_000_000);
            report.append("\n[%s %s by %s] %d ms%n%s%n".formatted(step.event().reference(), step.event().eventType(),
                    step.event().authorPart(), latenciesMs.getLast(),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary)));
            // 근거 좌표는 지금까지 넣은 이벤트의 것만 쓸 수 있다.
            for (String evidence : evidence(summary)) {
                assertThat(references).as("evidence %s after %s", evidence, step.event().reference()).contains(evidence);
            }
        }
        List<Long> sorted = latenciesMs.stream().sorted().toList();
        report.append("%n지연(ms): 중앙값 %d, 최대 %d%n".formatted(sorted.get(sorted.size() / 2), sorted.getLast()));
        // 품질 신호는 실패로 만들지 않고 적는다. 같은 입력에도 실행마다 달라질 수 있어 여러 번 돌려 비교한다.
        boolean agreementKept = summary.common().size() + summary.changed().size() > 0;
        boolean openIssueKept = evidence(summary.issues()).stream().anyMatch(reference -> reference.startsWith("9:"));
        report.append("합의 사항 유지: %s, 미해결 쟁점(세션 만료 응답 코드) 포착: %s%n"
                .formatted(agreementKept ? "예" : "아니오", openIssueKept ? "예" : "아니오"));
        System.out.println(report);
    }

    private static List<Step> steps() {
        String loginTask = "로그인 응답의 delivery 필드 처리";
        String sessionTask = "세션 만료 응답 코드";
        QuestionRef nullQuestion = new QuestionRef("7:2", AgentPart.BACKEND,
                "delivery가 null인 경우는 수신자가 오프라인일 때입니다. null 대신 status=DELIVERY_NOT_SENT 객체로 채우면 될까요?");
        return List.of(
                new Step(new NewEvent(7L, 1L, "7:1", "CREATED", AgentPart.FRONTEND, "PENDING", loginTask,
                        "로그인 API 응답에서 delivery 필드가 null로 올 때가 있어요. 화면이 깨지니 항상 채워서 보내 주세요."),
                        new Hints(List.of("delivery"), List.of(), 0, 0, false)),
                new Step(new NewEvent(7L, 2L, "7:2", "QUESTION", AgentPart.BACKEND, "IN_PROGRESS", loginTask,
                        nullQuestion.text()),
                        new Hints(List.of("delivery", "DELIVERY_NOT_SENT"), List.of(nullQuestion), 1, 0, false)),
                new Step(new NewEvent(7L, 3L, "7:3", "ANSWER", AgentPart.FRONTEND, "IN_PROGRESS", loginTask,
                        "네, null 대신 항상 객체로 채워 주세요. 화면은 delivery.status만 봅니다."),
                        new Hints(List.of("delivery", "delivery.status"), List.of(), 1, 1, false)),
                new Step(new NewEvent(7L, 4L, "7:4", "STATUS_CHANGED", AgentPart.BACKEND, "COMPLETED", loginTask,
                        "delivery를 항상 객체로 반환하도록 수정했습니다. 오프라인이면 status=DELIVERY_NOT_SENT, "
                                + "reasonCode=RECEIVER_OFFLINE입니다."),
                        new Hints(List.of("delivery", "DELIVERY_NOT_SENT", "RECEIVER_OFFLINE"), List.of(), 1, 1, false)),
                new Step(new NewEvent(9L, 1L, "9:1", "CREATED", AgentPart.FRONTEND, "PENDING", sessionTask,
                        "세션이 만료되면 401이 아니라 200에 빈 본문이 옵니다. 401로 통일해 주세요."),
                        new Hints(List.of("401", "200"), List.of(), 0, 0, false)),
                new Step(new NewEvent(9L, 2L, "9:2", "PROGRESS", AgentPart.BACKEND, "IN_PROGRESS", sessionTask,
                        "웹 세션은 이미 401을 반환하고 Agent 토큰은 403입니다. 200이 온 요청 경로를 확인해야 해서 "
                                + "지금은 바꿀 수 없습니다."),
                        new Hints(List.of("401", "403", "200"), List.of(), 0, 0, false)));
    }

    private static List<String> evidence(List<TeamContextSummary.Issue> issues) {
        List<String> all = new ArrayList<>();
        issues.forEach(item -> all.addAll(item.evidence()));
        return all;
    }

    private static List<String> evidence(TeamContextSummary summary) {
        List<String> all = new ArrayList<>();
        summary.common().forEach(item -> all.addAll(item.evidence()));
        summary.changed().forEach(item -> all.addAll(item.evidence()));
        summary.issues().forEach(item -> all.addAll(item.evidence()));
        return all;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
