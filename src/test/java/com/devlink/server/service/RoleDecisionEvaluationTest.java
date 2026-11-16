package com.devlink.server.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import com.devlink.server.client.DecisionsApiRoleDecisionClient;
import com.devlink.server.config.DecisionModelSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 역할 태그 판정 평가 세트(recommendation/role-decision-eval.txt)를 다룬다.
 * 토큰 일치만 쓴 판정(도입 전)과 결정 모델을 더한 판정(도입 후)을 같은 사례로 비교한다.
 * 실제 모델 측정은 기본 테스트 실행에서 건너뛴다. 실행하려면 환경 변수 DECISION_MODEL_EVAL=true와
 * DECISION_MODEL_API_KEY를 두고 {@code ./gradlew test --tests '*RoleDecisionEvaluationTest' -i}를 실행한다.
 * 주소·모델·키 필요 여부는 운영과 같은 DECISION_MODEL_URL, DECISION_MODEL_MODEL, DECISION_MODEL_REQUIRE_API_KEY로 바꾼다.
 * 질문마다 운영 경로와 같이 토큰으로 일치하지 않은 태그만 한 번에 묻는다.
 */
class RoleDecisionEvaluationTest {
    private static final String RESOURCE = "/recommendation/role-decision-eval.txt";
    private static final double[] THRESHOLDS = {0.5, 0.6, 0.7, 0.8, 0.9};
    /** 토큰으로 일치하는 사례만 두는 유형. 나머지 유형의 사례는 토큰으로 일치하지 않아야 한다. */
    private static final Set<String> TOKEN_MATCHING_TYPES = Set.of("token-match", "token-false-match");
    private static final int ATTEMPTS = 3;

    @Test
    void evaluationSetIsWellFormed() throws IOException {
        List<Case> cases = load();

        assertThat(cases).hasSizeGreaterThanOrEqualTo(150);
        assertThat(cases.stream().map(item -> item.tag() + "|" + item.question())).doesNotHaveDuplicates();
        assertThat(cases).anyMatch(Case::expected).anyMatch(item -> !item.expected());
        // 유형이 토큰 일치 여부를 정한다. 토큰으로 일치하지 않는 유형에서만 결정 모델이 결과를 바꿀 수 있다.
        for (Case item : cases) {
            assertThat(item.tokenMatch()).as(item.toString())
                    .isEqualTo(TOKEN_MATCHING_TYPES.contains(item.type()));
        }
        // 토큰 일치가 틀린 일치를 만드는 사례는 token-false-match 유형에만 있다.
        assertThat(cases).filteredOn(item -> item.tokenMatch() && !item.expected())
                .allMatch(item -> item.type().equals("token-false-match"));
        assertThat(cases).filteredOn(item -> item.type().equals("token-false-match")).noneMatch(Case::expected);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DECISION_MODEL_EVAL", matches = "true")
    void reportsAccuracyAndLatencyAgainstTheRealModel() throws IOException {
        DecisionModelSettings settings = RoleDecisionBenchmarkSupport.settingsFromEnvironment(2000);
        RoleDecisionBenchmarkSupport.Usage usage = new RoleDecisionBenchmarkSupport.Usage();
        DecisionsApiRoleDecisionClient client = RoleDecisionBenchmarkSupport.client(settings, usage);

        Map<String, List<Case>> byQuestion = new LinkedHashMap<>();
        load().forEach(item -> byQuestion.computeIfAbsent(item.question(), key -> new ArrayList<>()).add(item));
        Map<Case, Double> probabilities = new LinkedHashMap<>();
        List<Long> latenciesMs = new ArrayList<>();
        int failedAttempts = 0;
        for (Map.Entry<String, List<Case>> entry : byQuestion.entrySet()) {
            List<String> tags = entry.getValue().stream().filter(item -> !item.tokenMatch()).map(Case::tag).toList();
            Map<String, Double> answers = Map.of();
            if (!tags.isEmpty()) {
                // 정확도 측정이 일시 실패로 비지 않게 다시 시도한다. 실패 횟수는 따로 센다.
                RuntimeException last = null;
                for (int attempt = 0; attempt < ATTEMPTS && answers.isEmpty(); attempt++) {
                    long started = System.nanoTime();
                    try {
                        answers = client.probabilities(entry.getKey(), tags);
                        latenciesMs.add((System.nanoTime() - started) / 1_000_000);
                    } catch (RuntimeException exception) {
                        failedAttempts++;
                        last = exception;
                    }
                }
                assertThat(answers).as("answers after %d attempts: %s", ATTEMPTS, last).isNotEmpty();
            }
            for (Case item : entry.getValue()) {
                probabilities.put(item, item.tokenMatch() ? 1.0 : answers.get(item.tag()));
            }
        }

        List<Case> cases = List.copyOf(probabilities.keySet());
        long positives = cases.stream().filter(Case::expected).count();
        StringBuilder report = new StringBuilder("\n== 역할 태그 판정 평가 (" + settings.model() + ") ==\n");
        report.append("사례 %d건(Y %d, N %d), 모델 호출 %d회, 실패한 시도 %d회%n".formatted(cases.size(), positives,
                cases.size() - positives, latenciesMs.size(), failedAttempts));
        report.append("도입 전(토큰 일치만): %s%n".formatted(Counts.of(cases, Case::tokenMatch)));
        for (double threshold : THRESHOLDS) {
            report.append("도입 후(임계값 %.1f): %s%n".formatted(threshold,
                    Counts.of(cases, item -> probabilities.get(item) >= threshold)));
        }
        report.append("유형별(임계값 %.1f) | 건수 | 도입 전 정답 | 도입 후 정답 | 도입 후 틀린 일치 | 도입 후 놓친 일치%n"
                .formatted(settings.threshold()));
        Map<String, List<Case>> byType = new LinkedHashMap<>();
        cases.forEach(item -> byType.computeIfAbsent(item.type(), key -> new ArrayList<>()).add(item));
        for (Map.Entry<String, List<Case>> entry : byType.entrySet()) {
            Counts before = Counts.of(entry.getValue(), Case::tokenMatch);
            Counts after = Counts.of(entry.getValue(), item -> probabilities.get(item) >= settings.threshold());
            report.append("  %s | %d | %d | %d | %d | %d%n".formatted(entry.getKey(), entry.getValue().size(),
                    before.correct(), after.correct(), after.falseMatch(), after.missed()));
        }
        report.append("모델 호출 지연(ms): %s%n".formatted(RoleDecisionBenchmarkSupport.latency(latenciesMs)));
        report.append("사용량: %s%n".formatted(usage.summary()));
        report.append("임계값 %.1f에서 도입 후에도 틀린 사례:%n".formatted(settings.threshold()));
        for (Case item : cases) {
            if ((probabilities.get(item) >= settings.threshold()) != item.expected()) {
                String source = item.tokenMatch() ? "토큰 일치" : "확률 %.2f".formatted(probabilities.get(item));
                report.append("  [%s] 기대 %s, %s | %s | %s%n".formatted(item.type(), item.expected() ? "Y" : "N", source,
                        item.tag(), item.question()));
            }
        }
        System.out.println(report);
        assertThat(latenciesMs).isNotEmpty();
    }

    private static List<Case> load() throws IOException {
        List<Case> cases = new ArrayList<>();
        String type = null;
        for (String line : RoleDecisionBenchmarkSupport.resourceLines(RESOURCE)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                type = line.substring(1, line.length() - 1).strip();
                assertThat(type).as(line).isNotEmpty();
                continue;
            }
            assertThat(type).as("type header before: " + line).isNotNull();
            String[] fields = line.split("\\|", 3);
            assertThat(fields).as(line).hasSize(3);
            assertThat(fields[0]).as(line).isIn("Y", "N");
            assertThat(fields[1].strip()).as(line).isNotEmpty();
            assertThat(fields[2].strip()).as(line).isNotEmpty();
            cases.add(new Case(type, fields[0].equals("Y"), fields[1].strip(), fields[2].strip()));
        }
        return cases;
    }

    private record Case(String type, boolean expected, String tag, String question) {
        /** 운영의 토큰 일치 판정과 같은 결과다. */
        boolean tokenMatch() {
            return RecommendationRoleMatcher.containsSequence(RecommendationTokenizer.tokenize(question),
                    RecommendationTokenizer.tokenize(tag));
        }
    }

    /** 맞힌 일치, 틀린 일치, 놓친 일치, 맞힌 불일치. */
    private record Counts(long matched, long falseMatch, long missed, long rejected) {
        static Counts of(List<Case> cases, Predicate<Case> predicted) {
            long matched = 0;
            long falseMatch = 0;
            long missed = 0;
            long rejected = 0;
            for (Case item : cases) {
                boolean yes = predicted.test(item);
                if (yes && item.expected()) {
                    matched++;
                } else if (yes) {
                    falseMatch++;
                } else if (item.expected()) {
                    missed++;
                } else {
                    rejected++;
                }
            }
            return new Counts(matched, falseMatch, missed, rejected);
        }

        long correct() {
            return matched + rejected;
        }

        @Override
        public String toString() {
            long total = matched + falseMatch + missed + rejected;
            String precision = matched + falseMatch == 0 ? "-" : "%.3f".formatted((double) matched / (matched + falseMatch));
            String recall = matched + missed == 0 ? "-" : "%.3f".formatted((double) matched / (matched + missed));
            return "정답 %d/%d, 맞힌 일치 %d, 틀린 일치 %d, 놓친 일치 %d, 정밀도 %s, 재현율 %s"
                    .formatted(correct(), total, matched, falseMatch, missed, precision, recall);
        }
    }
}
