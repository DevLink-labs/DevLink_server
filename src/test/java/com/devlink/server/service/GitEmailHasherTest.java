package com.devlink.server.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GitEmailHasherTest {
    private static final String USER_EMAIL_HASH =
            "b4c9a289323b21a01c3e940f150eb9b8c542587f1abfd8f0e1cc1ffc5e475514";

    @Test
    void normalizationProducesTheKnownSha256Value() {
        assertThat(GitEmailHasher.hash("  User@Example.COM \t")).isEqualTo(USER_EMAIL_HASH);
        assertThat(GitEmailHasher.hash("user@example.com")).isEqualTo(USER_EMAIL_HASH);
    }

    @Test
    void comparisonAcceptsNormalizedMatchAndRejectsOtherEmailOrMissingHash() {
        assertThat(GitEmailHasher.matches(" USER@EXAMPLE.COM ", USER_EMAIL_HASH)).isTrue();
        assertThat(GitEmailHasher.matches("another@example.com", USER_EMAIL_HASH)).isFalse();
        assertThat(GitEmailHasher.matches("user@example.com", null)).isFalse();
    }
}
