package com.devlink.server.client;

import com.devlink.server.config.TeamContextSettings;
import com.devlink.server.dto.teamcontext.TeamContextInferenceRequest;
import com.devlink.server.dto.teamcontext.TeamContextSummary;
import com.devlink.server.exception.TeamContextInferenceException;
import com.devlink.server.prompt.TeamContextPrompt;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * OpenAI 호환 chat/completions 클라이언트다. OpenRouter와 vLLM이 같은 형식을 쓰므로
 * base-url·api-key·model 설정만 바꿔 호스팅 API에서 자체 GPU 서버로 옮길 수 있다.
 */
public class OpenAiCompatibleTeamContextInferenceClient implements TeamContextInferenceClient {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final TeamContextSettings settings;

    public OpenAiCompatibleTeamContextInferenceClient(RestClient client, ObjectMapper mapper,
            TeamContextSettings settings) {
        this.client = client;
        this.mapper = mapper;
        this.settings = settings;
    }

    @Override
    public String model() {
        return settings.model();
    }

    @Override
    public TeamContextSummary infer(TeamContextInferenceRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", settings.model());
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", TeamContextPrompt.SYSTEM);
        messages.addObject().put("role", "user").put("content", TeamContextPrompt.user(mapper, request));
        body.set("response_format", TeamContextPrompt.responseFormat(mapper));
        // reasoning 설정은 보내지 않는다. 기본 모델(openai/gpt-6-luna)은 기본으로 추론 토큰을 조금 쓰고 그만큼 max_tokens에서
        // 빠진다(15개 항목 요약에서 약 170토큰). 추론하지 않는 모델에는 영향이 없다.
        // 요약은 배열당 max-items개까지 커질 수 있으므로 max_tokens는 가득 찬 요약과 추론 토큰을 함께 담을 만큼 둔다.
        body.put("max_tokens", settings.maxOutputTokens());
        if (settings.openRouterProviderGuard()) {
            // OpenRouter 전용: 스키마를 못 받는 제공자와 입력을 학습에 쓰는 제공자로 라우팅하지 않는다.
            body.putObject("provider").put("require_parameters", true).put("data_collection", "deny");
        }
        JsonNode response;
        try {
            response = client.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        if (!settings.apiKey().isEmpty()) {
                            headers.setBearerAuth(settings.apiKey());
                        }
                    })
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException exception) {
            // 키·본문은 남기지 않고 상태 코드와 응답 앞부분만 남겨 운영 로그에서 원인을 볼 수 있게 한다.
            String detail = exception.getResponseBodyAsString();
            throw new TeamContextInferenceException("Inference request failed with HTTP "
                    + exception.getStatusCode().value() + ": "
                    + (detail.length() > 300 ? detail.substring(0, 300) : detail), exception);
        } catch (RestClientException exception) {
            throw new TeamContextInferenceException("Inference request failed: " + exception.getMessage(), exception);
        }
        // 한도에서 잘린 출력은 같은 입력으로 다시 보내도 같은 지점에서 잘린다. 온전한 JSON이 왔으면 그대로 쓰고,
        // 내용이 없거나 끊겼을 때만 원인을 로그에서 바로 알 수 있게 구분한다.
        boolean cutOff = response != null
                && "length".equals(response.path("choices").path(0).path("finish_reason").asText(null));
        String content = response == null ? null
                : response.path("choices").path(0).path("message").path("content").asText(null);
        if (content == null || content.isBlank()) {
            throw cutOff ? cutOff(null) : new TeamContextInferenceException("Inference returned no content");
        }
        try {
            return mapper.readValue(stripFence(content), TeamContextSummary.class)
                    .normalized(settings.maxItems(), settings.maxInputChars());
        } catch (JsonProcessingException exception) {
            throw cutOff ? cutOff(exception) : new TeamContextInferenceException("Inference returned invalid JSON", exception);
        }
    }

    private TeamContextInferenceException cutOff(Exception cause) {
        return new TeamContextInferenceException("Inference output was cut off at max_tokens (" + settings.maxOutputTokens()
                + "); raise TEAM_CONTEXT_INFERENCE_MAX_OUTPUT_TOKENS (devlink.team-context.inference.max-output-tokens)", cause);
    }

    /** 스키마 강제가 없는 제공자가 ```json 울타리를 붙여도 본문만 읽는다. */
    static String stripFence(String content) {
        String trimmed = content.strip();
        if (trimmed.startsWith("```")) {
            int firstLineEnd = trimmed.indexOf('\n');
            int closing = trimmed.lastIndexOf("```");
            if (firstLineEnd > 0 && closing > firstLineEnd) {
                return trimmed.substring(firstLineEnd + 1, closing).strip();
            }
        }
        return trimmed;
    }
}
