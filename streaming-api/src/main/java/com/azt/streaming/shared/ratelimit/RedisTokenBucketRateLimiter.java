package com.azt.streaming.shared.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Token bucket held in Redis, so the limit is shared by every instance.
 *
 * <p>The refill arithmetic runs inside a Lua script because it has to be atomic: read, refill,
 * decide and write as one step. Split into separate commands, two instances checking the same key
 * concurrently both observe enough tokens and both allow the request.
 */
@Slf4j
public class RedisTokenBucketRateLimiter implements RateLimiter {

    static final String KEY_PREFIX = "aztcast:v1:rl:";

    private final StringRedisTemplate template;
    private final RedisScript<List> script;
    private final Clock clock;
    private final int capacity;
    private final double refillPerMilli;

    public RedisTokenBucketRateLimiter(
            StringRedisTemplate template, RedisScript<List> script, Clock clock, int capacity, int refillPerHour) {
        this.template = template;
        this.script = script;
        this.clock = clock;
        this.capacity = capacity;
        this.refillPerMilli = refillPerHour / (double) Duration.ofHours(1).toMillis();
    }

    @Override
    public RateLimitDecision tryConsume(String key) {
        try {
            List<?> result = template.execute(
                    script,
                    List.of(KEY_PREFIX + key),
                    String.valueOf(capacity),
                    String.valueOf(refillPerMilli),
                    String.valueOf(clock.millis()),
                    "1");
            if (result == null || result.size() < 3) {
                return RateLimitDecision.allowed(capacity);
            }
            long allowed = ((Number) result.get(0)).longValue();
            long remaining = ((Number) result.get(1)).longValue();
            long retryMillis = ((Number) result.get(2)).longValue();
            return allowed == 1
                    ? RateLimitDecision.allowed(remaining)
                    : RateLimitDecision.denied(Duration.ofMillis(retryMillis));
        } catch (RuntimeException e) {
            // Fail open, and say so loudly. Fail-closed would turn a cache outage into a total
            // ingestion outage — trading a real service for a protective one. The limiter exists to
            // bound abuse, not to be a dependency of correctness.
            log.warn("Rate limiter unavailable; allowing the request", e);
            return RateLimitDecision.allowed(capacity);
        }
    }
}
