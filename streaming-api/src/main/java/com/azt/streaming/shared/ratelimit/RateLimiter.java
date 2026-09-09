package com.azt.streaming.shared.ratelimit;

/** Decides whether a caller may perform another expensive operation right now. */
public interface RateLimiter {

    /**
     * Consumes one token for {@code key}.
     *
     * <p>Implementations must not throw when their backing store is unavailable. A rate limiter is a
     * safety device; if it fails closed it becomes the outage, and if it throws it becomes a 500.
     */
    RateLimitDecision tryConsume(String key);
}
