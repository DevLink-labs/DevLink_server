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
            // 다른 서비스의 키를 OpenRouter로 보내거나, 자체 호스팅 주소로 바꾸면서 남겨 둔 OpenRouter 키를 그 주소로 보내지 않는다.
            // 이벤트가 실패로 누락되지 않게 구조 신호 전용으로 동작한다. 값은 남기지 않는다.
            boolean openRouter = settings.openRouter();
            log.warn("Team context inference: the API key does not belong to the configured endpoint ({}); the key is "
                            + "not sent and summaries stay structural. {}",
                    openRouter ? "OpenRouter, key is not an OpenRouter key" : "not OpenRouter, key is an OpenRouter key",
                    openRouter ? "Put an OpenRouter key in OPENROUTER_API_KEY"
                            : "Remove OPENROUTER_API_KEY for a self-hosted endpoint, or set "
                                    + "TEAM_CONTEXT_INFERENCE_OPENROUTER_GUARD=true if this address is a proxy in front "
                                    + "of OpenRouter");
        }
        if (!settings.llmConfigured()) {
            if (settings.openRouter() && settings.apiKey().isEmpty()) {
                // 이전 이름 UPSTAGE_API_KEY에 키를 둔 배포가 이름을 옮기지 않으면 이 상태가 된다.
                log.info("Team context inference: OPENROUTER_API_KEY is empty, so summaries stay structural "
                        + "(UPSTAGE_API_KEY is no longer read)");
            }
            return new StructuralTeamContextInferenceClient();
        }
        if (!settings.openRouter() && TeamContextSettings.DEFAULT_OPENROUTER_MODEL.equals(settings.model())) {
            // 주소만 자체 호스팅 서버로 바꾸고 모델을 지정하지 않은 구성. 그 서버가 모르는 모델 ID라 호출이 실패한다.
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
