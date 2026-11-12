package com.devlink.server.config;

import java.net.http.HttpClient;
import java.time.Duration;
import com.devlink.server.client.OpenAiCompatibleTeamContextInferenceClient;
import com.devlink.server.client.StructuralTeamContextInferenceClient;
import com.devlink.server.client.TeamContextInferenceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** base-url과 model이 설정되고 필요한 키가 있으면 OpenAI 호환 LLM을, 아니면 구조 신호 전용 구현을 쓴다. */
@Configuration
public class TeamContextInferenceConfig {
    private static final Logger log = LoggerFactory.getLogger(TeamContextInferenceConfig.class);

    @Bean
    public TeamContextInferenceClient teamContextInferenceClient(TeamContextSettings settings,
            RestClient.Builder builder, ObjectMapper mapper) {
        if (settings.keyBelongsElsewhere()) {
            // 기본 주소가 Upstage에서 OpenRouter로 바뀌었다. 예전 키만 남은 배포나, 제공자를 바꾸면서 OpenRouter 키를 남겨 둔
            // 배포에서 그 키를 다른 업체로 보내지 않고, 이벤트가 실패로 누락되지 않게 구조 신호 전용으로 동작한다. 값은 남기지 않는다.
            log.warn("Team context inference: the API key does not belong to the configured endpoint ({}); the key is "
                    + "not sent and summaries stay structural. Put the key for that endpoint in OPENROUTER_API_KEY, or "
                    + "point TEAM_CONTEXT_INFERENCE_BASE_URL and TEAM_CONTEXT_INFERENCE_MODEL at the provider the key "
                    + "belongs to", settings.openRouter() ? "OpenRouter, key is not an OpenRouter key"
                            : "not OpenRouter, key is an OpenRouter key; if this address is a proxy in front of "
                                    + "OpenRouter set TEAM_CONTEXT_INFERENCE_OPENROUTER_GUARD=true");
        }
        if (!settings.llmConfigured()) {
            return new StructuralTeamContextInferenceClient();
        }
        if (!settings.openRouter() && TeamContextSettings.DEFAULT_OPENROUTER_MODEL.equals(settings.model())) {
            // 주소만 다른 제공자로 바꾸고 모델을 지정하지 않은 구성. 그 제공자가 모르는 모델 ID라 호출이 실패한다.
            log.warn("Team context inference uses a non-OpenRouter endpoint with the OpenRouter default model {}; "
                    + "set TEAM_CONTEXT_INFERENCE_MODEL to a model that endpoint serves", settings.model());
        }
        if (settings.openRouter() && !settings.openRouterProviderGuard()) {
            log.warn("Team context inference calls OpenRouter without the provider guard; requests may be routed to "
                    + "providers that ignore the JSON schema or collect inputs");
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(settings.timeoutMs()));
        RestClient client = builder.baseUrl(settings.baseUrl()).requestFactory(factory).build();
        return new OpenAiCompatibleTeamContextInferenceClient(client, mapper, settings);
    }
}
