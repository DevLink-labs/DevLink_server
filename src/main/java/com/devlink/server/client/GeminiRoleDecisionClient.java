package com.devlink.server.client;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.devlink.server.config.DecisionModelSettings;
import com.devlink.server.exception.RoleDecisionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Gemini Decisions API(`POST /v1beta/decisions`) 클라이언트다.
 * 질문을 input으로, 태그마다 noul(예/아니오) 질문 하나를 보내 한 번의 호출로 모든 태그의 일치 확률을 받는다.
 * 텍스트를 생성하지 않으며, 응답은 확률 숫자만 읽는다.
 */
public class GeminiRoleDecisionClient implements RoleDecisionClient {
    /** 한 요청에 묻는 태그 수 상한이다. API 한도(1,000)보다 작게 잡아 입력 크기와 지연을 제한한다. */
    static final int MAX_TAGS = 200;
    private static final Logger log = LoggerFactory.getLogger(GeminiRoleDecisionClient.class);
    private static final String INSTRUCTIONS = """
            The input is a question that a developer wants to send to the teammate responsible for its topic.
            A teammate declared the responsibility tag below. Answer yes only if the question is clearly about \
            the area this tag names, including the same concept written in another language or as a synonym. \
            Answer no if the link is loose or indirect, if the tag claims every topic instead of naming \
            a specific area, or if the tag is not a description of a work area.
            Tag: """;

    private final RestClient client;
    private final ObjectMapper mapper;
    private final DecisionModelSettings settings;

    public GeminiRoleDecisionClient(RestClient client, ObjectMapper mapper, DecisionModelSettings settings) {
        this.client = client;
        this.mapper = mapper;
        this.settings = settings;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Set<String> matchedTags(String question, List<String> tags) {
        if (tags.isEmpty()) {
            return Set.of();
        }
        try {
            Set<String> matched = new LinkedHashSet<>();
            probabilities(question, tags).forEach((tag, probability) -> {
                if (probability >= settings.threshold()) {
                    matched.add(tag);
                }
            });
            return Set.copyOf(matched);
        } catch (RuntimeException exception) {
            // 키·질문·응답 본문은 남기지 않는다. 원인 구분에 필요한 요약만 남기고 토큰 일치로 계속한다.
            String reason = exception instanceof RoleDecisionException
                    ? exception.getMessage() : exception.getClass().getSimpleName();
            log.warn("Role decision model unavailable, using token matching only: {}", reason);
            return Set.of();
        }
    }

    /** 태그별 일치 확률. 중복 태그는 한 번만 묻고 {@link #MAX_TAGS}개를 넘는 태그는 묻지 않는다. 실패하면 예외다. */
    public Map<String, Double> probabilities(String question, List<String> tags) {
        List<String> asked = tags.stream().distinct().limit(MAX_TAGS).toList();
        ObjectNode body = mapper.createObjectNode();
        body.put("model", settings.model());
        body.put("input", question);
        ObjectNode questions = body.putObject("questions");
        for (int index = 0; index < asked.size(); index++) {
            // 질문 키는 ASCII 제약이 있어 태그 원문 대신 순번을 쓴다.
            questions.putObject(key(index)).put("type", "noul")
                    .put("instructions", INSTRUCTIONS + " " + asked.get(index).replaceAll("\\s+", " "));
        }
        JsonNode response;
        try {
            response = client.post()
                    .uri("/v1beta/decisions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("x-goog-api-key", settings.apiKey())
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException exception) {
            throw new RoleDecisionException("HTTP " + exception.getStatusCode().value());
        } catch (RestClientException exception) {
            throw new RoleDecisionException("request failed (" + exception.getClass().getSimpleName() + ")");
        }
        JsonNode answers = response == null ? null : response.get("answers");
        if (answers == null || !answers.isObject()) {
            throw new RoleDecisionException("response has no answers");
        }
        Map<String, Double> probabilities = new LinkedHashMap<>();
        for (int index = 0; index < asked.size(); index++) {
            JsonNode noul = answers.path(key(index)).path("noul");
            // 일부 답만 온 응답은 신뢰하지 않는다. 한 태그라도 빠지면 요청 전체를 실패로 본다.
            if (!noul.isNumber() || !(noul.asDouble() >= 0 && noul.asDouble() <= 1)) {
                throw new RoleDecisionException("response has a missing or invalid answer");
            }
            probabilities.put(asked.get(index), noul.asDouble());
        }
        return probabilities;
    }

    private static String key(int index) {
        return "t" + index;
    }
}
