package com.devlink.server.service;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RecommendationScorePolicyTest {
    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");

    @ParameterizedTest
    @CsvSource({"0, 0", "1, 20", "2, 40", "3, 60", "7, 60"})
    void roleScoreIsTwentyPerTagCappedAtThree(int tags, double expected) {
        assertThat(RecommendationScorePolicy.role(tags)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "1, 3", "3, 9", "5, 15", "9, 15"})
    void frequencyScoreIsThreePerCommitCappedAtFive(int commits, double expected) {
        assertThat(RecommendationScorePolicy.frequency(commits)).isEqualTo(expected);
    }

    @Test
    void workingScoreIsFifteenRegardlessOfFileCount() {
        assertThat(RecommendationScorePolicy.working(true)).isEqualTo(15.0);
        assertThat(RecommendationScorePolicy.working(false)).isZero();
    }

    @ParameterizedTest
    @CsvSource({"0, 10.00", "2, 9.06", "14, 5.00", "28, 2.50"})
    void recencyHalvesEveryFourteenDays(long daysAgo, double expectedDisplay) {
        double recency = RecommendationScorePolicy.recency(NOW.minus(Duration.ofDays(daysAgo)), NOW);

        assertThat(RecommendationScorePolicy.display(recency)).isEqualTo(expectedDisplay);
    }

    @Test
    void recencyUsesFractionalDaysAndTreatsFutureCommitsAsNow() {
        double halfDay = RecommendationScorePolicy.recency(NOW.minus(Duration.ofHours(12)), NOW);
        assertThat(halfDay).isCloseTo(10 * Math.pow(2, -0.5 / 14), within(1e-9));
        assertThat(RecommendationScorePolicy.recency(NOW.plusSeconds(60), NOW)).isEqualTo(10.0);
        assertThat(RecommendationScorePolicy.recency(null, NOW)).isZero();
    }

    @Test
    void displayRoundsHalfUpToTwoDecimals() {
        assertThat(RecommendationScorePolicy.display(67.0649)).isEqualTo(67.06);
        assertThat(RecommendationScorePolicy.display(63.505)).isEqualTo(63.51);
        assertThat(RecommendationScorePolicy.display(40)).isEqualTo(40.0);
    }
}
