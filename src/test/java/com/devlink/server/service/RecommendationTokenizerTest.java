package com.devlink.server.service;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class RecommendationTokenizerTest {

    @Test
    void questionSplitsCamelCaseAndKeepsKoreanWordsWithParticles() {
        List<String> tokens = RecommendationTokenizer.tokenize(
                "로그인을 처리하는 인증 흐름에서 refreshToken을 어떻게 반환해?");

        assertThat(tokens).containsExactly(
                "로그인을", "처리하는", "인증", "흐름에서", "refresh", "token", "을", "어떻게", "반환해");
    }

    @ParameterizedTest
    @CsvSource({
            "API에서, api 에서",
            "HTTPServer, http server",
            "RefreshToken rotation, refresh token rotation",
            "ｒｅｆｒｅｓｈ　Ｔｏｋｅｎ, refresh token",
            "oauth2Token, oauth2 token",
            "결제-승인/환불, 결제 승인 환불"
    })
    void tokenizeAppliesNfkcBoundarySplitAndLowercase(String input, String expected) {
        assertThat(String.join(" ", RecommendationTokenizer.tokenize(input))).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "?!…"})
    void blankOrSymbolOnlyTextHasNoTokens(String input) {
        assertThat(RecommendationTokenizer.tokenize(input)).isEmpty();
    }

    @Test
    void pathTokensDropExtensionShortTokensAndNumbers() {
        assertThat(RecommendationTokenizer.tokenizePath("src/auth/RefreshTokenController.java"))
                .containsExactlyInAnyOrder("src", "auth", "refresh", "token", "controller");
        assertThat(RecommendationTokenizer.tokenizePath("docs/v2/1234/a/README.md"))
                .containsExactlyInAnyOrder("docs", "v2", "readme");
        assertThat(RecommendationTokenizer.tokenizePath(".gitignore"))
                .containsExactly("gitignore");
        assertThat(RecommendationTokenizer.tokenizePath("App.test.js"))
                .containsExactlyInAnyOrder("app", "test");
    }

    @Test
    void pathMatchableQuestionTokensExcludeSingleCharactersAndDigits() {
        List<String> tokens = RecommendationTokenizer.tokenize("v2 API 3 을 token 2024");

        assertThat(RecommendationTokenizer.pathMatchableTokens(tokens))
                .containsExactly("v2", "api", "token");
    }
}
