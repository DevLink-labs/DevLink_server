package com.devlink.server.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.devlink.server.client.GeminiRoleDecisionClient;
import com.devlink.server.config.DecisionModelSettings;
import com.devlink.server.config.RoleDecisionConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 역할 태그 판정 평가 세트(recommendation/role-decision-eval.txt)를 다룬다.
 * 실제 모델 측정은 기본 테스트 실행에서 건너뛴다. 실행하려면 환경 변수 DECISION_MODEL_EVAL=true와
 * DECISION_MODEL_API_KEY를 두고 {@code ./gradlew test --tests '*RoleDecisionEvaluationTest' -i}를 실행한다.
 * 질문마다 운영 경로와 같이 토큰으로 일치하지 않은 태그만 한 번에 묻는다.
 */
class RoleDecisionEvaluationTest {
    private static final String RESOURCE = "/recommendation/role-decision-eval.txt";
    private static final double[] THRESHOLDS = {0.5, 0.6, 0.7, 0.8, 0.9};

    @Test
    void evaluationSetIsWellFormed() throws IOException {
        List<Case> cases = load();

        assertThat(cases).hasSizeGreaterThanOrEqualTo(30).doesNotHaveDuplicates();
        assertThat(cases).anyMatch(Case::expected).anyMatch(item -> !item.expected());
        // 토큰 일치는 이 세트에서 틀린 일치를 만들지 않는다. 모델을 켰을 때 달라지는 것은 놓친 일치뿐이다.
        assertThat(cases).filteredOn(Case::tokenMatch).allMatch(Case::expected);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DECISION_MODEL_EVAL", matches = "true")
    void reportsAccuracyAndLatencyAgainstTheRealModel() throws IOException {
        String apiKey = System.getenv("DECISION_MODEL_API_KEY");
        assertThat(apiKey).as("DECISION_MODEL_API_KEY").isNotBlank();
        DecisionModelSettings settings = new DecisionModelSettings(true,
                env("DECISION_MODEL_BASE_URL", "https://generativelanguage.googleapis.com"), apiKey,
                env("DECISION_MODEL_MODEL", "gemini-decision-1"), 2000, 0.7);
        GeminiRoleDecisionClient client = (GeminiRoleDecisionClient) new RoleDecisionConfig()
                .roleDecisionClient(settings, RestClient.builder(), new ObjectMapper());

        Map<String, List<Case>> byQuestion = new LinkedHashMap<>();
        load().forEach(item -> byQuestion.computeIfAbsent(item.question(), key -> new ArrayList<>()).add(item));
        Map<Case, Double> probabilities = new LinkedHashMap<>();
        List<Long> latenciesMs = new ArrayList<>();
        for (Map.Entry<String, List<Case>> entry : byQuestion.entrySet()) {
            List<String> tags = entry.getValue().stream().filter(item -> !item.tokenMatch()).map(Case::tag).toList();
            Map<String, Double> answers = Map.of();
            if (!tags.isEmpty()) {
                long started = System.nanoTime();
                answers = client.probabilities(entry.getKey(), tags);
                latenciesMs.add((System.nanoTime() - started) / 1_000_000);
            }
            for (Case item : entry.getValue()) {
                probabilities.put(item, item.tokenMatch() ? 1.0 : answers.get(item.tag()));
            }
        }

        StringBuilder report = new StringBuilder("\n== 역할 태그 판정 평가 (" + settings.model() + ") ==\n");
        List<Case> cases = List.copyOf(probabilities.keySet());
        long tokenCorrect = cases.stream().filter(item -> item.tokenMatch() == item.expected()).count();
        report.append("사례 %d건, 모델 호출 %d회%n".formatted(cases.size(), latenciesMs.size()));
        report.append("토큰 일치만: 정확 %d/%d%n".formatted(tokenCorrect, cases.size()));
        for (double threshold : THRESHOLDS) {
            long correct = 0;
            long falseMatch = 0;
            long missed = 0;
            for (Case item : cases) {
                boolean predicted = probabilities.get(item) >= threshold;
                correct += predicted == item.expected() ? 1 : 0;
                falseMatch += predicted && !item.expected() ? 1 : 0;
                missed += !predicted && item.expected() ? 1 : 0;
            }
            report.append("임계값 %.1f: 정확 %d/%d, 틀린 일치 %d, 놓친 일치 %d%n"
                    .formatted(threshold, correct, cases.size(), falseMatch, missed));
        }
        List<Long> sorted = latenciesMs.stream().sorted().toList();
        report.append("지연(ms): 중앙값 %d, p95 %d, 최대 %d%n".formatted(sorted.get(sorted.size() / 2),
                sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.95) - 1)),
                sorted.getLast()));
        report.append("임계값 %.1f에서 틀린 사례:%n".formatted(settings.threshold()));
        for (Case item : cases) {
            if ((probabilities.get(item) >= settings.threshold()) != item.expected()) {
                report.append("  기대 %s, 확률 %.2f | %s | %s%n".formatted(item.expected() ? "Y" : "N",
                        probabilities.get(item), item.tag(), item.question()));
            }
        }
        System.out.println(report);
        assertThat(latenciesMs).isNotEmpty();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static List<Case> load() throws IOException {
        try (InputStream stream = RoleDecisionEvaluationTest.class.getResourceAsStream(RESOURCE)) {
            assertThat(stream).as(RESOURCE).isNotNull();
            List<Case> cases = new ArrayList<>();
            for (String line : new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\\|", 3);
                assertThat(fields).as(line).hasSize(3);
                assertThat(fields[0]).as(line).isIn("Y", "N");
                assertThat(fields[1].strip()).as(line).isNotEmpty();
                assertThat(fields[2].strip()).as(line).isNotEmpty();
                cases.add(new Case(fields[0].equals("Y"), fields[1].strip(), fields[2].strip()));
            }
            return cases;
        }
    }

    private record Case(boolean expected, String tag, String question) {
        /** 운영의 토큰 일치 판정과 같은 결과다. */
        boolean tokenMatch() {
            return RecommendationRoleMatcher.containsSequence(RecommendationTokenizer.tokenize(question),
                    RecommendationTokenizer.tokenize(tag));
        }
    }
}
