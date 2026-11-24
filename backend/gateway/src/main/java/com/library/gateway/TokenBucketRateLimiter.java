package com.library.gateway;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * In-process token bucket per client key. Good enough for a single gateway instance; with several
 * instances this would move to Redis ({@code RequestRateLimiter} filter) so limits are shared.
 */
public class TokenBucketRateLimiter {

    /** Outcome of a single {@link #tryConsume} call. */
    public record Decision(boolean allowed, long retryAfterSeconds) {}

    private record Bucket(double tokens, long updatedAtNanos) {}

    private static final int MAX_KEYS = 10_000;

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int capacity;
    private final double refillPerSecond;
    private final LongSupplier nanoClock;

    public TokenBucketRateLimiter(int capacity, double refillPerSecond, LongSupplier nanoClock) {
        if (capacity <= 0 || refillPerSecond <= 0) {
            throw new IllegalArgumentException("capacity and refillPerSecond must be positive");
        }
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.nanoClock = nanoClock;
    }

    public Decision tryConsume(String key) {
        if (buckets.size() > MAX_KEYS) {
            buckets.clear(); // crude but bounded: protects memory against key-spraying clients
        }
        long now = nanoClock.getAsLong();
        boolean[] allowed = new boolean[1];
        Bucket bucket = buckets.compute(key, (k, current) -> {
            double tokens = current == null
                    ? capacity
                    : Math.min(capacity, current.tokens() + (now - current.updatedAtNanos()) / 1e9 * refillPerSecond);
            allowed[0] = tokens >= 1;
            return new Bucket(allowed[0] ? tokens - 1 : tokens, now);
        });
        long retryAfter = allowed[0] ? 0 : (long) Math.ceil((1 - bucket.tokens()) / refillPerSecond);
        return new Decision(allowed[0], Math.max(retryAfter, allowed[0] ? 0 : 1));
    }
}
