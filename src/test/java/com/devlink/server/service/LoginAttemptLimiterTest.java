package com.devlink.server.service;

import com.devlink.server.exception.LoginRateLimitException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class LoginAttemptLimiterTest {
    private final AtomicLong nanoTime = new AtomicLong();
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(nanoTime::get);

    @Test
    void limitsOneAccountAcrossDifferentAddressesUntilItsWindowExpires() {
        String email = "target@example.com";
        for (int attempt = 1; attempt <= 10; attempt++) {
            assertThat(limiter.verify("192.0.2." + attempt, email, () -> true)).isTrue();
        }

        assertThat(rejected("192.0.2.11", email).getRetryAfterSeconds()).isEqualTo(300);
        nanoTime.addAndGet(Duration.ofSeconds(299).toNanos());
        assertThat(rejected("192.0.2.12", email).getRetryAfterSeconds()).isEqualTo(1);
        nanoTime.addAndGet(Duration.ofSeconds(1).toNanos());

        assertThat(limiter.verify("192.0.2.12", email, () -> true)).isTrue();
    }

    @Test
    void limitsOneAddressAcrossDifferentAccounts() {
        for (int attempt = 1; attempt <= 60; attempt++) {
            assertThat(limiter.verify("192.0.2.1", email(attempt), () -> true)).isTrue();
        }

        assertThat(rejected("192.0.2.1", email(61)).getRetryAfterSeconds()).isEqualTo(60);
        assertThat(limiter.verify("192.0.2.2", email(61), () -> true)).isTrue();
    }

    @Test
    void appliesOneLimitToAnIpv6Network() {
        for (int attempt = 1; attempt <= 60; attempt++) {
            assertThat(limiter.verify("2001:db8:abcd:1::" + Integer.toHexString(attempt),
                    email(attempt), () -> true)).isTrue();
        }

        assertThat(rejected("2001:0db8:abcd:0001:0:0:0:ffff", email(61)).getRetryAfterSeconds()).isEqualTo(60);
        assertThat(limiter.verify("2001:db8:abcd:2::1", email(61), () -> true)).isTrue();
    }

    @Test
    void limitsTrafficAcrossBothDifferentAccountsAndAddresses() {
        for (int attempt = 1; attempt <= 120; attempt++) {
            assertThat(limiter.verify("192.0.2." + attempt, email(attempt), () -> true)).isTrue();
        }

        assertThat(rejected("192.0.2.121", email(121)).getRetryAfterSeconds()).isEqualTo(60);
    }

    @Test
    void limitsConcurrentChecksAndReleasesCapacityAfterVerification() throws Exception {
        var started = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        Supplier<Boolean> verifyCredentials = () -> {
            started.countDown();
            try {
                return release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> limiter.verify("192.0.2.1", email(1), verifyCredentials));
            var second = executor.submit(() -> limiter.verify("192.0.2.2", email(2), verifyCredentials));
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(rejected("192.0.2.3", email(3)).getRetryAfterSeconds()).isPositive();
            } finally {
                release.countDown();
            }

            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(limiter.verify("192.0.2.3", email(3), () -> true)).isTrue();
        }
    }

    @Test
    void releasesCapacityWhenVerificationThrows() {
        var invalidCredentials = new IllegalArgumentException("인증 실패");
        for (int attempt = 1; attempt <= 2; attempt++) {
            String email = email(attempt);
            assertThat(catchThrowableOfType(() -> limiter.verify("192.0.2.1", email, () -> {
                throw invalidCredentials;
            }), IllegalArgumentException.class)).isSameAs(invalidCredentials);
        }

        assertThat(limiter.verify("192.0.2.1", email(3), () -> true)).isTrue();
    }

    private LoginRateLimitException rejected(String address, String email) {
        return catchThrowableOfType(() -> limiter.verify(address, email, () -> {
            throw new AssertionError("제한된 요청의 비밀번호 검증 실행");
        }), LoginRateLimitException.class);
    }

    private String email(int index) {
        return "user" + index + "@example.com";
    }
}
