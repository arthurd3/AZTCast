package com.azt.streaming.shared.ratelimit;

import java.time.Duration;

/**
 * @param allowed whether this request may proceed
 * @param remaining tokens left in the bucket afterwards, for the response headers
 * @param retryAfter how long until one token is available; zero when allowed
 */
public record RateLimitDecision(boolean allowed, long remaining, Duration retryAfter) {

    public static RateLimitDecision allowed(long remaining) {
        return new RateLimitDecision(true, remaining, Duration.ZERO);
    }

    public static RateLimitDecision denied(Duration retryAfter) {
        return new RateLimitDecision(false, 0, retryAfter);
    }
}
