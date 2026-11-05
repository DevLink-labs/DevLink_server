package com.devlink.server.service;

import java.util.List;
import java.util.stream.IntStream;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.AgentPart;
import com.devlink.server.domain.agent.ToolType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

class RecommendationRoleMatcherTest {
    private static final List<String> QUESTION = RecommendationTokenizer.tokenize(
            "로그인을 처리하는 인증 흐름에서 refreshToken을 어떻게 반환해?");

    @ParameterizedTest
    @CsvSource({
            "로그인, 로그인을, true",
            "인증, 인증의, true",
            "결제 승인, 결제 승인을, true",
            "결제 승인, 결제 승인에서, true",
            "로그인, 로그인화면, false",
            "결제 승인, 결제의 승인을, false",
            "token, token을, true",
            "token, tokens, false",
            "refresh token, refreshToken을 반환, true"
    })
    // 영문 태그 뒤의 한글 조사는 토큰화 단계에서 이미 분리되므로 조사 규칙 없이도 일치한다.
    void tagMatchesWhenTokenSequenceAppearsWithOptionalKoreanParticle(String tag, String question, boolean expected) {
        boolean matched = RecommendationRoleMatcher.containsSequence(
                RecommendationTokenizer.tokenize(question), RecommendationTokenizer.tokenize(tag));

        assertThat(matched).isEqualTo(expected);
    }

    @Test
    void explicitRolesAreUsedAndEachCountedOnce() {
        Agent agent = agent(List.of("로그인", "인증", "결제", "로그인", " 인증 "), "회원, 가입");

        assertThat(RecommendationRoleMatcher.matchedRoles(agent, QUESTION)).containsExactly("로그인", "인증");
    }

    @Test
    void roleDescriptionIsFallbackOnlyWhenRolesAreEmpty() {
        Agent agent = agent(List.of(), "로그인, 인증 / 회원;refresh token|결제\n환불");

        assertThat(RecommendationRoleMatcher.candidateTags(agent).keySet())
                .containsExactly("로그인", "인증", "회원", "refresh token", "결제", "환불");
        assertThat(RecommendationRoleMatcher.matchedRoles(agent, QUESTION))
                .containsExactly("로그인", "인증", "refresh token");
    }

    @Test
    void fallbackTagsAreLimitedToTen() {
        String description = String.join(",", IntStream.rangeClosed(1, 12).mapToObj(i -> "태그" + i).toList());
        Agent agent = agent(List.of(), description);

        assertThat(RecommendationRoleMatcher.candidateTags(agent)).hasSize(RecommendationRoleMatcher.MAX_TAGS);
    }

    @Test
    void agentWithoutTagsNeverMatches() {
        assertThat(RecommendationRoleMatcher.matchedRoles(agent(List.of(), null), QUESTION)).isEmpty();
        assertThat(RecommendationRoleMatcher.matchedRoles(agent(List.of(" ", "?"), " , "), QUESTION)).isEmpty();
    }

    private static Agent agent(List<String> roles, String description) {
        return Agent.create(1L, 2L, "agent", description, ToolType.CODEX, "project", AgentPart.BACKEND, roles);
    }
}
