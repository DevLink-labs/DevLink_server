package com.devlink.server.dto.recommendation;

/** 요소별 점수. 모두 소수 둘째 자리로 반올림한 표시 값이다. */
public record RecommendationScoresResponse(
        double role,
        double git,
        double working,
        double frequency,
        double recency) {
}
