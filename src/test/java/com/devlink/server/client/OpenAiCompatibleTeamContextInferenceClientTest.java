package com.devlink.server.client;

import java.util.List;
import com.devlink.server.config.TeamContextSettings;
import com.devlink.server.domain.agent.AgentPart;
import com.devlink.server.dto.teamcontext.TeamContextInferenceRequest;
import com.devlink.server.dto.teamcontext.TeamContextInferenceRequest.Hints;
import com.devlink.server.dto.teamcontext.TeamContextInferenceRequest.NewEvent;
import com.devlink.server.dto.teamcontext.TeamContextSummary;
import com.devlink.server.exception.TeamContextInferenceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Upstage·OpenRouter·vLLM이 공유하는 chat/completions 형식과 응답 해석을 검증한다. 실제 네트워크는 쓰지 않는다. */
class OpenAiCompatibleTeamContextInferenceClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.upstage.ai/v1");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    @Test
    void sendsSchemaConstrainedRequestWithBearerKeyAndParsesContent() throws Exception {
        TeamContextSettings settings = settings("solar-pro4", "test-key", false);
        var client = new OpenAiCompatibleTeamContextInferenceClient(builder.build(), mapper, settings);
        String content = """
                {"common":[{"statement":" 세션 쿠키 인증 ","evidence":["7:1","7:1"]}],
                 "changed":[{"before":"delivery null 허용","after":"항상 채움","evidence":["7:4"]}],
                 "issues":[{"topic":"404 vs 200","positions":[{"part":"frontend","position":"404"}],"evidence":["7:3"]}]}
                """;
        server.expect(requestTo("https://api.upstage.ai/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(request -> {
                    JsonNode body = mapper.readTree(request.getBody().toString());
                    assertThat(body.path("model").asText()).isEqualTo("solar-pro4");
                    assertThat(body.path("messages").size()).isEqualTo(2);
                    assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
                    JsonNode user = mapper.readTree(body.path("messages").get(1).path("content").asText());
                    assertThat(user.path("event").path("reference").asText()).isEqualTo("7:4");
                    assertThat(user.path("previous").path("common")).isEmpty();
                    JsonNode format = body.path("response_format");
                    assertThat(format.path("type").asText()).isEqualTo("json_schema");
                    assertThat(format.path("json_schema").path("strict").asBoolean()).isTrue();
                    JsonNode schema = format.path("json_schema").path("schema");
                    assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
                    assertThat(schema.path("required")).hasSize(3);
                    assertThat(body.path("max_tokens").asInt()).isEqualTo(2000);
                    assertThat(body.has("provider")).isFalse();
                    assertThat(body.has("temperature")).isFalse();
                })
                .andRespond(withSuccess(completion(content), MediaType.APPLICATION_JSON));

        TeamContextSummary summary = client.infer(request());
        server.verify();
        assertThat(summary.common()).singleElement().satisfies(item -> {
            assertThat(item.statement()).isEqualTo("세션 쿠키 인증");
            assertThat(item.evidence()).containsExactly("7:1");
        });
        assertThat(summary.changed()).singleElement()
                .satisfies(item -> assertThat(item.after()).isEqualTo("항상 채움"));
        assertThat(summary.issues()).singleElement()
                .satisfies(item -> assertThat(item.positions().getFirst().part()).isEqualTo("FRONTEND"));
        assertThat(client.model()).isEqualTo("solar-pro4");
    }

    @Test
    void addsOpenRouterProviderGuardOnlyWhenEnabledAndStripsCodeFences() throws Exception {
        TeamContextSettings settings = settings("qwen/qwen3-14b", "", true);
        var client = new OpenAiCompatibleTeamContextInferenceClient(builder.build(), mapper, settings);
        server.expect(requestTo("https://api.upstage.ai/v1/chat/completions"))
                .andExpect(request -> {
                    assertThat(request.getHeaders().containsKey("Authorization")).isFalse();
                    JsonNode provider = mapper.readTree(request.getBody().toString()).path("provider");
                    assertThat(provider.path("require_parameters").asBoolean()).isTrue();
                    assertThat(provider.path("data_collection").asText()).isEqualTo("deny");
                })
                .andRespond(withSuccess(completion("```json\n{\"common\":[],\"changed\":[],\"issues\":[]}\n```"),
                        MediaType.APPLICATION_JSON));
        assertThat(client.infer(request())).isEqualTo(TeamContextSummary.empty());
        server.verify();
    }

    @Test
    void reportsHttpFailuresEmptyContentAndInvalidJsonAsInferenceExceptions() throws Exception {
        TeamContextSettings settings = settings("solar-pro4", "test-key", false);
        var client = new OpenAiCompatibleTeamContextInferenceClient(builder.build(), mapper, settings);
        server.expect(requestTo("https://api.upstage.ai/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo("https://api.upstage.ai/v1/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":null},\"finish_reason\":\"length\"}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.upstage.ai/v1/chat/completions"))
                .andRespond(withSuccess(completion("not json"), MediaType.APPLICATION_JSON));
        for (String expected : List.of("request failed", "no content", "invalid JSON")) {
            assertThatThrownBy(() -> client.infer(request()))
                    .isInstanceOf(TeamContextInferenceException.class)
                    .hasMessageContaining(expected);
        }
        server.verify();
    }

    private String completion(String content) throws Exception {
        var root = mapper.createObjectNode();
        root.putArray("choices").addObject().put("finish_reason", "stop")
                .putObject("message").put("role", "assistant").put("content", content);
        return mapper.writeValueAsString(root);
    }

    private static TeamContextSettings settings(String model, String apiKey, boolean guard) {
        return new TeamContextSettings(true, "https://api.upstage.ai/v1", apiKey, true, model, 1000, 6000, 20, 2000,
                "v1", guard);
    }

    private static TeamContextInferenceRequest request() {
        return new TeamContextInferenceRequest(1L, AgentPart.BACKEND, AgentPart.FRONTEND, 0,
                TeamContextSummary.empty(),
                new NewEvent(7L, 4L, "7:4", "ANSWER", AgentPart.BACKEND, "IN_PROGRESS", "로그인 응답", "항상 채움"),
                new Hints(List.of("delivery"), List.of(), 1, 1, false));
    }
}
