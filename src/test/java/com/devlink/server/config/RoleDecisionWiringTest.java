package com.devlink.server.config;

import com.devlink.server.client.GeminiRoleDecisionClient;
import com.devlink.server.client.RoleDecisionClient;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.service.AgentPresenceService;
import com.devlink.server.service.AgentRecommendationService;
import com.devlink.server.service.RecommendationGitEvidenceService;
import com.devlink.server.service.TeamAuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * application.yml의 환경 변수 이름이 결정 모델 설정까지 이어지는지와, 추천 서비스가 그 클라이언트로 조립되는지 확인한다.
 * DB와 네트워크 없이 필요한 빈만 올린다.
 */
class RoleDecisionWiringTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // 개발자 셸에 평가용 DECISION_MODEL_* 변수가 있어도 결과가 달라지지 않게 OS 환경 변수를 뺀다.
            .withInitializer(context -> context.getEnvironment().getPropertySources()
                    .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withBean(AgentRepository.class, () -> mock(AgentRepository.class))
            .withBean(TeamAuthorizationService.class, () -> mock(TeamAuthorizationService.class))
            .withBean(RecommendationGitEvidenceService.class, () -> mock(RecommendationGitEvidenceService.class))
            .withBean(AgentPresenceService.class, () -> mock(AgentPresenceService.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withUserConfiguration(DecisionModelSettings.class, RoleDecisionConfig.class,
                    AgentRecommendationService.class);

    @Test
    void modelIsOffByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(AgentRecommendationService.class);
            assertThat(context.getBean(DecisionModelSettings.class).configured()).isFalse();
            assertThat(context.getBean(RoleDecisionClient.class).enabled()).isFalse();
        });
    }

    @Test
    void enablingWithoutKeyOrKeyWithoutEnablingKeepsTheModelOff() {
        runner.withPropertyValues("DECISION_MODEL_ENABLED=true")
                .run(context -> assertThat(context.getBean(RoleDecisionClient.class).enabled()).isFalse());
        runner.withPropertyValues("DECISION_MODEL_API_KEY=synthetic-decision-key-never-print")
                .run(context -> assertThat(context.getBean(RoleDecisionClient.class).enabled()).isFalse());
    }

    @Test
    void enabledWithKeyUsesTheGeminiClientWithEnvironmentOverrides() {
        runner.withPropertyValues("DECISION_MODEL_ENABLED=true",
                        "DECISION_MODEL_API_KEY=synthetic-decision-key-never-print",
                        "DECISION_MODEL_TIMEOUT_MS=800", "DECISION_MODEL_THRESHOLD=0.85")
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentRecommendationService.class);
                    assertThat(context.getBean(RoleDecisionClient.class)).isInstanceOf(GeminiRoleDecisionClient.class);
                    DecisionModelSettings settings = context.getBean(DecisionModelSettings.class);
                    assertThat(settings.model()).isEqualTo("gemini-decision-1");
                    assertThat(settings.baseUrl()).isEqualTo("https://generativelanguage.googleapis.com");
                    assertThat(settings.timeoutMs()).isEqualTo(800);
                    assertThat(settings.threshold()).isEqualTo(0.85);
                });
    }
}
