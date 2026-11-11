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
        // Upstage Solar는 reasoning_effort를 보내지 않으면 추론 없이 답하므로 max_tokens가 본문에 그대로 쓰인다.
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
        String content = response == null ? null
                : response.path("choices").path(0).path("message").path("content").asText(null);
        if (content == null || content.isBlank()) {
            throw new TeamContextInferenceException("Inference returned no content");
        }
        try {
            return mapper.readValue(stripFence(content), TeamContextSummary.class)
                    .normalized(settings.maxItems(), settings.maxInputChars());
        } catch (JsonProcessingException exception) {
            throw new TeamContextInferenceException("Inference returned invalid JSON", exception);
        }
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
