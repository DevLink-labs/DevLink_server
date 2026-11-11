package com.devlink.server.config;

import java.net.http.HttpClient;
import java.time.Duration;
import com.devlink.server.client.OpenAiCompatibleTeamContextInferenceClient;
import com.devlink.server.client.StructuralTeamContextInferenceClient;
import com.devlink.server.client.TeamContextInferenceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** base-url과 model이 설정되면 OpenAI 호환 LLM을, 아니면 구조 신호 전용 구현을 쓴다. */
@Configuration
public class TeamContextInferenceConfig {
    @Bean
    public TeamContextInferenceClient teamContextInferenceClient(TeamContextSettings settings,
            RestClient.Builder builder, ObjectMapper mapper) {
        if (!settings.llmConfigured()) {
            return new StructuralTeamContextInferenceClient();
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(settings.timeoutMs()));
        RestClient client = builder.baseUrl(settings.baseUrl()).requestFactory(factory).build();
        return new OpenAiCompatibleTeamContextInferenceClient(client, mapper, settings);
    }
}
