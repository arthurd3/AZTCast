package com.azt.streaming.shared.ratelimit;

import java.time.Duration;

/**
 * Raised when a caller exceeds its budget.
 *
 * <p>A plain exception, carrying no HTTP or problem-detail types. That is what lets the rate limiter
 * live outside {@code shared.error} while still producing the same RFC 9457 response shape as every
 * other failure: it throws, and the error boundary — which is allowed to know about everything —
 * decides what that looks like on the wire.
 */
public class RateLimitExceededException extends RuntimeException {

    private final transient Duration retryAfter;

    public RateLimitExceededException(String key, Duration retryAfter) {
        super("Rate limit exceeded for %s; retry in %ds".formatted(key, retryAfter.toSeconds()));
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
