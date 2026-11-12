package com.devlink.server.config;

import com.devlink.server.client.DecisionsApiRoleDecisionClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionModelSettingsTest {
    private static final String URL = "https://openrouter.ai/api/alpha/decisions";
    private static final String MODEL = "google/gemini-decision-1";
    private static final String KEY = "synthetic-decision-key-never-print";

    private static DecisionModelSettings settings(boolean enabled, String url, String apiKey, boolean requireApiKey) {
        return new DecisionModelSettings(enabled, url, apiKey, requireApiKey, MODEL, 1500, 0.7);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "true, key, true, true",
            "true, '', true, false",
            "true, '   ', true, false",
            "false, key, true, false",
            "false, '', true, false",
            // 자체 호스팅: 키가 필요 없다고 설정하면 키 없이 켜진다.
            "true, '', false, true",
            "true, key, false, true",
            "false, '', false, false"
    })
    void modelIsUsedOnlyWhenEnabledAndTheKeyRequirementIsMet(boolean enabled, String apiKey, boolean requireApiKey,
            boolean expected) {
        DecisionModelSettings settings = settings(enabled, URL, apiKey, requireApiKey);

        assertThat(settings.configured()).isEqualTo(expected);
        assertThat(new RoleDecisionConfig().roleDecisionClient(settings, RestClient.builder(), new ObjectMapper())
                .enabled()).isEqualTo(expected);
    }

    @Test
    void configuredSettingsCreateTheDecisionsClientAndBlankUrlOrModelDisablesIt() {
        assertThat(new RoleDecisionConfig().roleDecisionClient(settings(true, URL, KEY, true), RestClient.builder(),
                new ObjectMapper())).isInstanceOf(DecisionsApiRoleDecisionClient.class);
        assertThat(settings(true, " ", KEY, true).configured()).isFalse();
        assertThat(new DecisionModelSettings(true, URL, KEY, true, null, 1500, 0.7).configured()).isFalse();
    }

    @Test
    void textFormNeverContainsTheKey() {
        assertThat(settings(true, URL, KEY, true).toString()).contains("apiKey=<set>").doesNotContain(KEY);
        assertThat(settings(true, URL, "", true).toString()).contains("apiKey=<empty>");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://decisions.example.com/v1/decisions", "http://localhost.example.net",
            "http://localhost@example.net", "https://key@openrouter.ai/api/alpha/decisions",
            "http://127.0.0.1.nip.io", "http://192.168.0.10:8000/decisions", "ftp://localhost", "localhost:8080",
            "https://", "not a url"})
    void aKeyIsNeverSentOverPlainHttpOutsideLoopbackOrToAMalformedUrl(String url) {
        assertThatThrownBy(() -> settings(true, url, KEY, true)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://openrouter.ai/api/alpha/decisions", "https://openrouter.ai/api/alpha/decisions/",
            "HTTPS://proxy.example.com/decisions", "http://127.0.0.1:8089/decisions", "http://localhost:8089",
            "http://[::1]:8089/decisions"})
    void httpsAndLoopbackHttpAreAcceptedWithAKey(String url) {
        assertThat(settings(true, url, KEY, true).configured()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://192.168.0.10:8000/decisions", "http://decisions.internal/v1/decisions"})
    void plainHttpToAnotherHostIsAcceptedOnlyWithoutAKey(String url) {
        assertThat(settings(true, url, "", false).configured()).isTrue();
        // 키가 필요한 구성에서 키가 비어 있으면 주소는 받아들이되 호출하지 않는다.
        assertThat(settings(true, url, "", true).configured()).isFalse();
        assertThatThrownBy(() -> settings(true, url, KEY, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://decisions.internal", "http://user@decisions.internal", "decisions.internal:8000"})
    void malformedUrlsAreRejectedEvenWithoutAKey(String url) {
        assertThatThrownBy(() -> settings(true, url, "", false)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"0, 0.7", "2001, 0.7", "1500, 0", "1500, 1", "1500, NaN"})
    void timeoutAndThresholdOutsideTheirRangesAreRejected(long timeoutMs, double threshold) {
        assertThatThrownBy(() -> new DecisionModelSettings(true, URL, KEY, true, MODEL, timeoutMs, threshold))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
