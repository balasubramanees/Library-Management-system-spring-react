package com.library.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

    private final AtomicLong nanos = new AtomicLong();
    private final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, 1, nanos::get);

    @Test
    void allowsBurstUpToCapacityThenRejects() {
        assertThat(limiter.tryConsume("a").allowed()).isTrue();
        assertThat(limiter.tryConsume("a").allowed()).isTrue();
        assertThat(limiter.tryConsume("a").allowed()).isTrue();

        TokenBucketRateLimiter.Decision rejected = limiter.tryConsume("a");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void refillsOverTime() {
        for (int i = 0; i < 3; i++) {
            limiter.tryConsume("a");
        }
        nanos.addAndGet(1_000_000_000L);
        assertThat(limiter.tryConsume("a").allowed()).isTrue();
        assertThat(limiter.tryConsume("a").allowed()).isFalse();
    }

    @Test
    void keysAreIndependent() {
        for (int i = 0; i < 3; i++) {
            limiter.tryConsume("a");
        }
        assertThat(limiter.tryConsume("b").allowed()).isTrue();
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new TokenBucketRateLimiter(0, 1, nanos::get)).isInstanceOf(IllegalArgumentException.class);
    }
}
