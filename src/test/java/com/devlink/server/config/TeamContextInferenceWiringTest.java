package com.devlink.server.config;

import com.devlink.server.client.OpenAiCompatibleTeamContextInferenceClient;
import com.devlink.server.client.StructuralTeamContextInferenceClient;
import com.devlink.server.client.TeamContextInferenceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.yml의 기본값과 환경 변수 이름이 팀·파트 Context 추론 설정까지 이어지는지 확인한다.
 * DB와 네트워크 없이 필요한 빈만 올린다.
 */
@ExtendWith(OutputCaptureExtension.class)
class TeamContextInferenceWiringTest {
    private static final String OPENROUTER_KEY = "sk-or-v1-synthetic-key-never-print";
    private static final String OTHER_KEY = "other-service-synthetic-key-never-print";
    private static final String KEY_MISMATCH = "does not belong to the configured endpoint";
    private static final String MODEL_MISMATCH = "non-OpenRouter endpoint with the OpenRouter default model";
    private static final String NO_GUARD = "without the provider guard";
    private static final String NO_KEY = "OPENROUTER_API_KEY is empty";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // 개발자 셸의 OPENROUTER_API_KEY 등이 결과를 바꾸지 않게 OS 환경 변수를 뺀다.
            .withInitializer(context -> context.getEnvironment().getPropertySources()
                    .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withUserConfiguration(TeamContextSettings.class, TeamContextInferenceConfig.class);

    @Test
    void withoutAKeyTheSummaryStaysStructural(CapturedOutput output) {
        runner.run(context -> {
            assertThat(context.getBean(TeamContextInferenceClient.class)).isInstanceOf(StructuralTeamContextInferenceClient.class);
            TeamContextSettings settings = context.getBean(TeamContextSettings.class);
            assertThat(settings.baseUrl()).isEqualTo("https://openrouter.ai/api/v1");
            assertThat(settings.model()).isEqualTo(TeamContextSettings.DEFAULT_OPENROUTER_MODEL).isEqualTo("openai/gpt-6-luna");
            assertThat(settings.maxOutputTokens()).isEqualTo(6000);
            assertThat(settings.llmConfigured()).isFalse();
        });
        assertThat(output.getAll()).contains(NO_KEY).doesNotContain(KEY_MISMATCH, MODEL_MISMATCH, NO_GUARD);
    }

    @Test
    void openRouterKeyAloneSelectsTheDefaultModelWithTheProviderGuard(CapturedOutput output) {
        runner.withPropertyValues("OPENROUTER_API_KEY=" + OPENROUTER_KEY).run(context -> {
            assertThat(context.getBean(TeamContextInferenceClient.class))
                    .isInstanceOf(OpenAiCompatibleTeamContextInferenceClient.class);
            TeamContextSettings settings = context.getBean(TeamContextSettings.class);
            assertThat(settings.apiKey()).isEqualTo(OPENROUTER_KEY);
            assertThat(settings.model()).isEqualTo("openai/gpt-6-luna");
            assertThat(settings.openRouterProviderGuard()).isTrue();
            assertThat(settings.toString()).contains("apiKey=<set>").doesNotContain(OPENROUTER_KEY);
        });
        assertThat(output.getAll()).doesNotContain(NO_KEY, KEY_MISMATCH, MODEL_MISMATCH, NO_GUARD, OPENROUTER_KEY);
    }

    @Test
    void theRemovedUpstageKeyNameIsNoLongerRead(CapturedOutput output) {
        // 이전 템플릿은 OpenRouter 키를 UPSTAGE_API_KEY에 두게 했다. 이름을 옮기지 않은 배포는 키가 없는 상태가 된다.
        runner.withPropertyValues("UPSTAGE_API_KEY=" + OPENROUTER_KEY,
                        "TEAM_CONTEXT_INFERENCE_BASE_URL=https://openrouter.ai/api/v1",
                        "TEAM_CONTEXT_INFERENCE_MODEL=openai/gpt-4o-mini", "TEAM_CONTEXT_INFERENCE_OPENROUTER_GUARD=true")
                .run(context -> {
                    assertThat(context.getBean(TeamContextInferenceClient.class))
                            .isInstanceOf(StructuralTeamContextInferenceClient.class);
                    assertThat(context.getBean(TeamContextSettings.class).apiKey()).isEmpty();
                });
        assertThat(output.getAll()).contains(NO_KEY).doesNotContain(OPENROUTER_KEY);
    }

    @Test
    void explicitModelAndGuardFromAnExistingDeploymentAreKept(CapturedOutput output) {
        runner.withPropertyValues("OPENROUTER_API_KEY=" + OPENROUTER_KEY,
                        "TEAM_CONTEXT_INFERENCE_BASE_URL=https://openrouter.ai/api/v1",
                        "TEAM_CONTEXT_INFERENCE_MODEL=openai/gpt-4o-mini", "TEAM_CONTEXT_INFERENCE_OPENROUTER_GUARD=true")
                .run(context -> {
                    TeamContextSettings settings = context.getBean(TeamContextSettings.class);
                    assertThat(settings.llmConfigured()).isTrue();
                    assertThat(settings.model()).isEqualTo("openai/gpt-4o-mini");
                    assertThat(settings.openRouterProviderGuard()).isTrue();
                });
        assertThat(output.getAll()).doesNotContain(NO_KEY, KEY_MISMATCH, MODEL_MISMATCH, NO_GUARD);
    }

    @Test
    void aKeyForAnotherServiceIsNeverSentToOpenRouter(CapturedOutput output) {
        // OpenRouter 키 형식이 아닌 키를 OPENROUTER_API_KEY에 둔 배포(예: 다른 서비스의 키를 옮겨 적은 경우).
        // 그 키를 OpenRouter로 보내거나 이벤트를 실패시키지 않고 구조 신호 전용으로 남는다.
        runner.withPropertyValues("OPENROUTER_API_KEY=" + OTHER_KEY).run(context -> {
            assertThat(context.getBean(TeamContextInferenceClient.class)).isInstanceOf(StructuralTeamContextInferenceClient.class);
            TeamContextSettings settings = context.getBean(TeamContextSettings.class);
            assertThat(settings.keyBelongsElsewhere()).isTrue();
            assertThat(settings.llmConfigured()).isFalse();
        });
        assertThat(output.getAll()).contains(KEY_MISMATCH).doesNotContain(NO_KEY, OTHER_KEY);
    }

    @Test
    void anOpenRouterKeyLeftBehindIsNeverSentToASelfHostedEndpoint(CapturedOutput output) {
        // 자체 호스팅 주소로 바꾸면서 OPENROUTER_API_KEY를 지우지 않은 배포.
        runner.withPropertyValues("OPENROUTER_API_KEY=" + OPENROUTER_KEY,
                        "TEAM_CONTEXT_INFERENCE_BASE_URL=http://vllm.internal:8000/v1", "TEAM_CONTEXT_INFERENCE_MODEL=local-model")
                .run(context -> assertThat(context.getBean(TeamContextInferenceClient.class))
                        .isInstanceOf(StructuralTeamContextInferenceClient.class));
        assertThat(output.getAll()).contains(KEY_MISMATCH).doesNotContain(OPENROUTER_KEY);
        // OpenRouter 앞의 프록시는 필터를 true로 명시한 구성으로 구분한다.
        runner.withPropertyValues("OPENROUTER_API_KEY=" + OPENROUTER_KEY,
                        "TEAM_CONTEXT_INFERENCE_BASE_URL=https://gateway.example.com/openrouter/v1",
                        "TEAM_CONTEXT_INFERENCE_OPENROUTER_GUARD=true")
                .run(context -> assertThat(context.getBean(TeamContextInferenceClient.class))
                        .isInstanceOf(OpenAiCompatibleTeamContextInferenceClient.class));
    }

    @Test
    void openRouterIsNeverCalledWithoutAKey() {
        // vLLM용 설정에서 주소를 빠뜨린 경우. 키 없이 OpenRouter를 부르면 모든 이벤트가 401로 실패한다.
        runner.withPropertyValues("TEAM_CONTEXT_INFERENCE_REQUIRE_API_KEY=false")
                .run(context -> assertThat(context.getBean(TeamContextInferenceClient.class))
                        .isInstanceOf(StructuralTeamContextInferenceClient.class));
    }

    @Test
    void aSelfHostedEndpointWithoutAModelIsReportedAtStartup(CapturedOutput output) {
        runner.withPropertyValues("TEAM_CONTEXT_INFERENCE_BASE_URL=http://vllm.internal:8000/v1",
                        "TEAM_CONTEXT_INFERENCE_REQUIRE_API_KEY=false")
                .run(context -> assertThat(context.getBean(TeamContextSettings.class).model()).isEqualTo("openai/gpt-6-luna"));
        assertThat(output.getAll()).contains(MODEL_MISMATCH);
    }

    @Test
    void selfHostedEndpointWorksWithoutAKeyAndWithoutTheOpenRouterField(CapturedOutput output) {
        runner.withPropertyValues("TEAM_CONTEXT_INFERENCE_BASE_URL=http://127.0.0.1:8000/v1",
                        "TEAM_CONTEXT_INFERENCE_MODEL=local-model", "TEAM_CONTEXT_INFERENCE_REQUIRE_API_KEY=false")
                .run(context -> {
                    assertThat(context.getBean(TeamContextInferenceClient.class))
                            .isInstanceOf(OpenAiCompatibleTeamContextInferenceClient.class);
                    assertThat(context.getBean(TeamContextSettings.class).openRouterProviderGuard()).isFalse();
                });
        assertThat(output.getAll()).doesNotContain(NO_KEY, KEY_MISMATCH, MODEL_MISMATCH, NO_GUARD);
    }

    @ParameterizedTest
    @CsvSource({
            // auto는 호스트가 openrouter.ai이거나 그 하위 도메인일 때만 켠다.
            "auto, https://openrouter.ai/api/v1, true",
            "auto, HTTPS://API.OPENROUTER.AI/v1, true",
            "auto, https://openrouter.ai./api/v1, true",
            "auto, https://openrouter.ai:443/api/v1, true",
            "auto, https://openrouter.ai.example.com/v1, false",
            "auto, https://gateway.example.com/openrouter/v1, false",
            "AUTO, https://openrouter.ai/api/v1, true",
            // true·false는 주소와 무관하게 그대로 따른다. 프록시 뒤의 OpenRouter는 true로 지정한다.
            "true, https://gateway.example.com/openrouter/v1, true",
            "false, https://openrouter.ai/api/v1, false"
    })
    void providerGuardModeIsResolvedFromTheSettingAndTheHost(String mode, String baseUrl, boolean expected) {
        runner.withPropertyValues("OPENROUTER_API_KEY=" + OPENROUTER_KEY, "TEAM_CONTEXT_INFERENCE_MODEL=some/model",
                        "TEAM_CONTEXT_INFERENCE_OPENROUTER_GUARD=" + mode, "TEAM_CONTEXT_INFERENCE_BASE_URL=" + baseUrl)
                .run(context -> assertThat(context.getBean(TeamContextSettings.class).openRouterProviderGuard())
                        .isEqualTo(expected));
    }

    @Test
    void turningTheGuardOffOnOpenRouterIsReportedAndAnUnknownModeStopsStartup(CapturedOutput output) {
        runner.withPropertyValues("OPENROUTER_API_KEY=" + OPENROUTER_KEY, "TEAM_CONTEXT_INFERENCE_OPENROUTER_GUARD=false")
                .run(context -> assertThat(context.getBean(TeamContextInferenceClient.class))
                        .isInstanceOf(OpenAiCompatibleTeamContextInferenceClient.class));
        assertThat(output.getAll()).contains(NO_GUARD);
        runner.withPropertyValues("TEAM_CONTEXT_INFERENCE_OPENROUTER_GUARD=sometimes")
                .run(context -> assertThat(context).hasFailed());
    }
}
