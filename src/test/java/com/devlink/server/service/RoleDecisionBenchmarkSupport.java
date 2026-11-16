package com.devlink.server.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import com.devlink.server.client.DecisionsApiRoleDecisionClient;
import com.devlink.server.config.DecisionModelSettings;
import com.devlink.server.config.RoleDecisionConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 결정 모델을 부르는 선택 실행 측정이 함께 쓰는 도구다.
 * 운영과 같은 설정 조립({@link RoleDecisionConfig})으로 클라이언트를 만들고, 응답의 사용량(입력 토큰, 비용)을 기록한다.
 * 키는 출력하지 않는다.
 */
final class RoleDecisionBenchmarkSupport {
    private RoleDecisionBenchmarkSupport() {
    }

    static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    static List<String> resourceLines(String resource) throws IOException {
        try (InputStream stream = RoleDecisionBenchmarkSupport.class.getResourceAsStream(resource)) {
            assertThat(stream).as(resource).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }

    /** 운영과 같은 환경 변수를 읽어 설정을 만든다. 키 없는 자체 호스팅 서버는 DECISION_MODEL_REQUIRE_API_KEY=false로 잰다. */
    static DecisionModelSettings settingsFromEnvironment(long timeoutMs) {
        boolean requireApiKey = !"false".equalsIgnoreCase(env("DECISION_MODEL_REQUIRE_API_KEY", "true"));
        String apiKey = env("DECISION_MODEL_API_KEY", "");
        if (requireApiKey) {
            assertThat(apiKey).as("DECISION_MODEL_API_KEY").isNotBlank();
        }
        return new DecisionModelSettings(true,
                env("DECISION_MODEL_URL", "https://openrouter.ai/api/alpha/decisions"), apiKey, requireApiKey,
                env("DECISION_MODEL_MODEL", "google/gemini-decision-1"), timeoutMs,
                Double.parseDouble(env("DECISION_MODEL_THRESHOLD", "0.7")));
    }

    /** 운영 설정 조립으로 만든 클라이언트. 2xx 응답마다 사용량을 {@code usage}에 더한다. */
    static DecisionsApiRoleDecisionClient client(DecisionModelSettings settings, Usage usage) {
        ObjectMapper mapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
            ClientHttpResponse response = execution.execute(request, body);
            byte[] bytes = response.getBody().readAllBytes();
            if (response.getStatusCode().is2xxSuccessful()) {
                usage.record(mapper.readTree(bytes));
            }
            return new BufferedResponse(response, bytes);
        });
        return (DecisionsApiRoleDecisionClient) new RoleDecisionConfig().roleDecisionClient(settings, builder, mapper);
    }

    static long percentile(List<Long> values, double fraction) {
        List<Long> sorted = values.stream().sorted().toList();
        return sorted.get(Math.min(sorted.size() - 1, Math.max(0, (int) Math.ceil(sorted.size() * fraction) - 1)));
    }

    static String latency(List<Long> millis) {
        if (millis.isEmpty()) {
            return "측정값 없음";
        }
        return "중앙값 %d, p95 %d, 최대 %d (n=%d)".formatted(percentile(millis, 0.5), percentile(millis, 0.95),
                percentile(millis, 1.0), millis.size());
    }

    /** 2xx 응답의 사용량 합계. 형식이 틀려 클라이언트가 버린 응답도 2xx면 센다. 없는 필드는 0으로 센다. */
    static final class Usage {
        private final List<Long> inputTokens = new ArrayList<>();
        private double upstreamCost;
        private double billedCost;

        synchronized void record(JsonNode response) {
            JsonNode usage = response.path("usage");
            inputTokens.add(usage.path("input_tokens").asLong(0));
            billedCost += usage.path("cost").asDouble(0);
            upstreamCost += usage.path("cost_details").path("upstream_inference_cost").asDouble(0);
        }

        synchronized int responses() {
            return inputTokens.size();
        }

        synchronized long totalInputTokens() {
            return inputTokens.stream().mapToLong(Long::longValue).sum();
        }

        /** OpenRouter가 청구한 비용과 제공자 원가 중 큰 값. 자기 키(BYOK) 계정은 청구 비용이 0으로 온다. */
        synchronized double cost() {
            return Math.max(billedCost, upstreamCost);
        }

        synchronized String summary() {
            int count = responses();
            if (count == 0) {
                return "2xx 응답 없음";
            }
            long total = totalInputTokens();
            return "응답 %d건, 입력 토큰 합계 %d(응답당 평균 %d), 비용 합계 $%.6f(응답당 평균 $%.6f)"
                    .formatted(count, total, total / count, cost(), cost() / count);
        }
    }

    /** 본문을 한 번 읽어 사용량을 기록한 뒤 호출자가 다시 읽을 수 있게 한다. */
    private static final class BufferedResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final byte[] body;

        private BufferedResponse(ClientHttpResponse delegate, byte[] body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
