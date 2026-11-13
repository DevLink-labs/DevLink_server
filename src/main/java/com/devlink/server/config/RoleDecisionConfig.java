package com.devlink.server.config;

import java.net.http.HttpClient;
import java.time.Duration;
import com.devlink.server.client.DecisionsApiRoleDecisionClient;
import com.devlink.server.client.RoleDecisionClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 결정 모델 설정이 갖춰지면 Decisions API 클라이언트를, 아니면 호출하지 않는 구현을 쓴다. */
@Configuration
public class RoleDecisionConfig {
    private static final Logger log = LoggerFactory.getLogger(RoleDecisionConfig.class);

    @Bean
    public RoleDecisionClient roleDecisionClient(DecisionModelSettings settings, RestClient.Builder builder,
            ObjectMapper mapper) {
        if (!settings.configured()) {
            if (settings.enabled()) {
                // 켰는데 조용히 꺼져 있는 상태를 운영자가 알 수 있게 한다. 값은 남기지 않는다.
                log.warn("Role decision model is enabled but not configured (url, model or required API key missing); "
                        + "using token matching only");
            }
            return RoleDecisionClient.disabled();
        }
        Duration timeout = Duration.ofMillis(settings.timeoutMs());
        // HTTP/1.1로 고정한다. JDK 클라이언트의 기본값(HTTP/2)은 평문 http 주소에 h2c 업그레이드 헤더를 붙이는데,
        // 팀·파트 Context 추론에서 vLLM 0.32.0(uvicorn) 서버가 그런 요청을 400으로 거절했다(DevLink_server#103).
        // https 주소(OpenRouter)도 HTTP/1.1을 쓰게 된다. 요청이 드물고 추천 한 번에 하나라 HTTP/2 다중화의 이점이 작다.
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        RestClient client = builder.requestFactory(factory).build();
        return new DecisionsApiRoleDecisionClient(client, mapper, settings);
    }
}
