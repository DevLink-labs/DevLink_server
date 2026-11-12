package com.devlink.server.config;

import java.net.http.HttpClient;
import java.time.Duration;
import com.devlink.server.client.GeminiRoleDecisionClient;
import com.devlink.server.client.RoleDecisionClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 결정 모델이 켜져 있고 키가 있으면 Gemini Decisions 클라이언트를, 아니면 호출하지 않는 구현을 쓴다. */
@Configuration
public class RoleDecisionConfig {
    @Bean
    public RoleDecisionClient roleDecisionClient(DecisionModelSettings settings, RestClient.Builder builder,
            ObjectMapper mapper) {
        if (!settings.configured()) {
            return RoleDecisionClient.disabled();
        }
        Duration timeout = Duration.ofMillis(settings.timeoutMs());
        HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        RestClient client = builder.baseUrl(settings.baseUrl()).requestFactory(factory).build();
        return new GeminiRoleDecisionClient(client, mapper, settings);
    }
}
