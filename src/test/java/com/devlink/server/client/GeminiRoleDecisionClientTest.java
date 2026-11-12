package com.devlink.server.client;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.stream.IntStream;
import com.devlink.server.config.DecisionModelSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Gemini Decisions API 요청 형식, 임계값 적용, 실패 시 빈 결과를 검증한다. 실제 네트워크는 쓰지 않는다. */
@ExtendWith(OutputCaptureExtension.class)
class GeminiRoleDecisionClientTest {
    private static final String URL = "https://generativelanguage.googleapis.com/v1beta/decisions";
    private static final String KEY = "synthetic-decision-key-never-print";
    private static final String QUESTION = "sign-in 버튼이 눌리지 않는 untrusted-question-do-not-log";

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient.Builder builder = RestClient.builder()
            .baseUrl("https://generativelanguage.googleapis.com");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final GeminiRoleDecisionClient client = new GeminiRoleDecisionClient(builder.build(), mapper,
            new DecisionModelSettings(true, "https://generativelanguage.googleapis.com", KEY, "gemini-decision-1",
                    1500, 0.7));

    @Test
    void asksOneNoulQuestionPerDistinctTagAndKeepsTagsAtOrAboveThreshold() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", KEY))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(request -> {
                    JsonNode body = mapper.readTree(request.getBody().toString());
                    assertThat(body.path("model").asText()).isEqualTo("gemini-decision-1");
                    assertThat(body.path("input").asText()).isEqualTo(QUESTION);
                    JsonNode questions = body.path("questions");
                    assertThat(questions).hasSize(3);
                    assertThat(questions.path("t0").path("type").asText()).isEqualTo("noul");
                    assertThat(questions.path("t0").path("instructions").asText()).endsWith("Tag: 로그인");
                    assertThat(questions.path("t1").path("instructions").asText()).endsWith("Tag: 결제 승인");
                    assertThat(questions.path("t2").path("instructions").asText()).endsWith("Tag: 배포");
                    assertThat(body.toString()).doesNotContain(KEY);
                })
                .andRespond(withSuccess(answers(0.93, 0.7, 0.69), MediaType.APPLICATION_JSON));

        // 중복 태그는 한 번만 묻고, 태그 안의 줄바꿈은 지시문 구조를 바꾸지 못하게 공백으로 편다.
        assertThat(client.matchedTags(QUESTION, List.of("로그인", "결제\n승인", "로그인", "배포")))
                .containsExactlyInAnyOrder("로그인", "결제\n승인");
        server.verify();
        assertThat(client.enabled()).isTrue();
    }

    @Test
    void asksAtMostTheTagLimit() {
        List<String> tags = IntStream.range(0, GeminiRoleDecisionClient.MAX_TAGS + 50)
                .mapToObj(index -> "tag" + index).toList();
        double[] high = IntStream.range(0, GeminiRoleDecisionClient.MAX_TAGS).mapToDouble(index -> 0.9).toArray();
        server.expect(requestTo(URL))
                .andExpect(request -> assertThat(mapper.readTree(request.getBody().toString()).path("questions"))
                        .hasSize(GeminiRoleDecisionClient.MAX_TAGS))
                .andRespond(withSuccess(answers(high), MediaType.APPLICATION_JSON));

        assertThat(client.matchedTags(QUESTION, tags)).hasSize(GeminiRoleDecisionClient.MAX_TAGS)
                .contains("tag0", "tag199").doesNotContain("tag200");
        server.verify();
    }

    @Test
    void emptyTagListMakesNoRequest() {
        assertThat(client.matchedTags(QUESTION, List.of())).isEmpty();
        server.verify();
    }

    @Test
    void everyFailureYieldsNoMatchesWithoutLoggingKeyQuestionOrResponseBody(CapturedOutput output) {
        String leak = "untrusted-response-body-do-not-log";
        List<ResponseCreator> failures = List.of(
                withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body(leak),
                withStatus(HttpStatus.TOO_MANY_REQUESTS).body(leak),
                withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"safety\",\"message\":\"" + leak + "\"}}"),
                withSuccess("not json " + leak, MediaType.APPLICATION_JSON),
                withSuccess("{\"usage\":{\"total_tokens\":12}}", MediaType.APPLICATION_JSON),
                // 일부 답만 온 응답, 범위를 벗어난 값, 숫자가 아닌 값은 요청 전체를 실패로 본다.
                withSuccess("{\"answers\":{\"t0\":{\"type\":\"noul\",\"noul\":0.99}}}", MediaType.APPLICATION_JSON),
                withSuccess(answers(0.99, 1.5), MediaType.APPLICATION_JSON),
                withSuccess("{\"answers\":{\"t0\":{\"noul\":0.99},\"t1\":{\"noul\":\"yes\"}}}",
                        MediaType.APPLICATION_JSON),
                withException(new SocketTimeoutException("timed out " + leak)));
        failures.forEach(failure -> server.expect(requestTo(URL)).andRespond(failure));

        for (int index = 0; index < failures.size(); index++) {
            assertThat(client.matchedTags(QUESTION, List.of("로그인", "결제"))).as("failure %d", index).isEmpty();
        }
        server.verify();
        // 이 클라이언트가 남긴 경고 줄만 본다. 테스트 기본 로그 수준에서 Spring이 남기는 DEBUG 줄은 대상이 아니다.
        List<String> warnings = output.getAll().lines()
                .filter(line -> line.contains("using token matching only")).toList();
        assertThat(warnings).hasSize(failures.size())
                .anyMatch(line -> line.contains("HTTP 500"))
                .anyMatch(line -> line.contains("HTTP 429"))
                .anyMatch(line -> line.contains("HTTP 400"))
                .noneMatch(line -> line.contains(KEY) || line.contains("untrusted-question-do-not-log")
                        || line.contains(leak));
    }

    private String answers(double... probabilities) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode answers = root.putObject("answers");
        for (int index = 0; index < probabilities.length; index++) {
            answers.putObject("t" + index).put("type", "noul").put("noul", probabilities[index])
                    .put("confidence", Math.max(probabilities[index], 1 - probabilities[index]));
        }
        root.putObject("usage").put("total_input_tokens", 42).put("total_output_tokens", 0);
        return root.toString();
    }
}
