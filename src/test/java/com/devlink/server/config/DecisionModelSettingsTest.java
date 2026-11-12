package com.devlink.server.config;

import com.devlink.server.client.GeminiRoleDecisionClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionModelSettingsTest {
    private static final String URL = "https://generativelanguage.googleapis.com";
    private static final String KEY = "synthetic-decision-key-never-print";

    @ParameterizedTest
    @CsvSource(value = {
            "true, key, true",
            "true, '', false",
            "true, '   ', false",
            "false, key, false",
            "false, '', false"
    })
    void modelIsUsedOnlyWhenEnabledAndKeyIsPresent(boolean enabled, String apiKey, boolean expected) {
        DecisionModelSettings settings = new DecisionModelSettings(enabled, URL, apiKey, "gemini-decision-1", 1500, 0.7);

        assertThat(settings.configured()).isEqualTo(expected);
        assertThat(new RoleDecisionConfig().roleDecisionClient(settings, RestClient.builder(), new ObjectMapper())
                .enabled()).isEqualTo(expected);
    }

    @Test
    void configuredSettingsCreateTheGeminiClientAndBlankUrlOrModelDisablesIt() {
        RoleDecisionConfig config = new RoleDecisionConfig();
        assertThat(config.roleDecisionClient(new DecisionModelSettings(true, URL, KEY, "gemini-decision-1", 1500, 0.7),
                RestClient.builder(), new ObjectMapper())).isInstanceOf(GeminiRoleDecisionClient.class);
        assertThat(new DecisionModelSettings(true, " ", KEY, "gemini-decision-1", 1500, 0.7).configured()).isFalse();
        assertThat(new DecisionModelSettings(true, URL, KEY, null, 1500, 0.7).configured()).isFalse();
    }

    @Test
    void textFormNeverContainsTheKey() {
        assertThat(new DecisionModelSettings(true, URL, KEY, "gemini-decision-1", 1500, 0.7).toString())
                .contains("apiKey=<set>").doesNotContain(KEY);
        assertThat(new DecisionModelSettings(true, URL, "", "gemini-decision-1", 1500, 0.7).toString())
                .contains("apiKey=<empty>");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://decisions.example.com", "http://localhost.example.net",
            "http://localhost@example.net", "https://key@generativelanguage.googleapis.com",
            "http://127.0.0.1.nip.io", "ftp://localhost", "localhost:8080", "https://", "not a url"})
    void plainHttpOutsideLoopbackAndMalformedUrlsAreRejected(String baseUrl) {
        assertThatThrownBy(() -> new DecisionModelSettings(true, baseUrl, KEY, "gemini-decision-1", 1500, 0.7))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://generativelanguage.googleapis.com", "https://generativelanguage.googleapis.com/",
            "HTTPS://proxy.example.com/gemini",
            "http://127.0.0.1:8089", "http://localhost:8089", "http://[::1]:8089"})
    void httpsAndLoopbackHttpAreAccepted(String baseUrl) {
        assertThat(new DecisionModelSettings(true, baseUrl, KEY, "gemini-decision-1", 1500, 0.7).configured()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"0, 0.7", "2001, 0.7", "1500, 0", "1500, 1", "1500, NaN"})
    void timeoutAndThresholdOutsideTheirRangesAreRejected(long timeoutMs, double threshold) {
        assertThatThrownBy(() -> new DecisionModelSettings(true, URL, KEY, "gemini-decision-1", timeoutMs, threshold))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
