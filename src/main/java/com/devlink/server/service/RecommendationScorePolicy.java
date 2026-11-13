package com.devlink.server.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * 담당 Agent 추천 점수 산식과 상수. 역할 60점 + Git 40점(현재 작업 15, 변경 횟수 15, 최근성 10)으로 최대 100점이다.
 * 계산과 비교는 반올림 전 값으로 하고, 응답에 넣을 때만 {@link #display}로 소수 둘째 자리로 반올림한다.
 */
public final class RecommendationScorePolicy {
    public static final int MAX_QUESTION_CODE_POINTS = 1000;
    public static final Duration COMMIT_WINDOW = Duration.ofDays(90);
    public static final int MAX_CANDIDATES = 5;
    public static final int MAX_RELATED_FILES = 5;
    public static final int PATH_QUERY_CHUNK_SIZE = 1000;
    public static final int MAX_IMPORTERS_PER_DIRECT_FILE = 20;
    public static final double MAX_INDIRECT_COMMIT_WEIGHT = 2.0;
    /** 최고점에서 이 점수 이내인 양의 후보를 함께 반환한다. */
    public static final double CANDIDATE_SCORE_GAP = 5.0;

    static final double ROLE_POINTS_PER_TAG = 20.0;
    static final int MAX_COUNTED_TAGS = 3;
    public static final double INDIRECT_WEIGHT = 0.5;
    static final double WORKING_POINTS = 15.0;
    static final double FREQUENCY_POINTS_PER_COMMIT = 3.0;
    static final int MAX_COUNTED_COMMITS = 5;
    static final double RECENCY_MAX_POINTS = 10.0;
    static final double RECENCY_HALF_LIFE_DAYS = 14.0;
    private static final double MILLIS_PER_DAY = 24 * 60 * 60 * 1000.0;

    private RecommendationScorePolicy() {
    }

    /** 역할 점수 = 20 × min(일치 태그 수, 3). */
    public static double role(int matchedTagCount) {
        return ROLE_POINTS_PER_TAG * Math.min(Math.max(matchedTagCount, 0), MAX_COUNTED_TAGS);
    }

    /** W = 15 × 유효한 작업 파일의 최대 가중치 (직접 1.0, 간접 0.5). */
    public static double working(double weight) {
        return WORKING_POINTS * Math.min(Math.max(weight, 0.0), 1.0);
    }

    /**
     * 변경 횟수 점수 F = 3 × min(직접 커밋 수 + min(간접 커밋 가중 합, 2.0), 5).
     * weightedCommitCount에는 간접 커밋 상한을 적용한 합을 전달한다.
     */
    public static double frequency(double weightedCommitCount) {
        return FREQUENCY_POINTS_PER_COMMIT * Math.min(Math.max(weightedCommitCount, 0), MAX_COUNTED_COMMITS);
    }

    /** 최근성 점수 D = 10 × 2^(-d/14). 관련 커밋이 없으면 0, 미래 시각은 경과 0일로 본다. */
    public static double recency(Instant latestCommitAt, Instant evaluatedAt) {
        if (latestCommitAt == null) {
            return 0.0;
        }
        double elapsedDays = Math.max(Duration.between(latestCommitAt, evaluatedAt).toMillis(), 0L) / MILLIS_PER_DAY;
        return RECENCY_MAX_POINTS * Math.pow(2.0, -elapsedDays / RECENCY_HALF_LIFE_DAYS);
    }

    /** 응답 표시용 소수 둘째 자리 반올림. */
    public static double display(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
