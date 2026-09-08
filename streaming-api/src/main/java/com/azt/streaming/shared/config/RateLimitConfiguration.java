package com.azt.streaming.shared.config;

import com.azt.streaming.shared.ratelimit.RateLimitDecision;
import com.azt.streaming.shared.ratelimit.RateLimiter;
import com.azt.streaming.shared.ratelimit.RedisTokenBucketRateLimiter;
import java.time.Clock;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.Optional;

/** Builds the rate limiter, or a permissive one when there is no Redis to hold the counters in. */
@Slf4j
@Configuration
public class RateLimitConfiguration {

    @Bean
    RateLimiter rateLimiter(
            StreamingProperties properties, Optional<RedisConnectionFactory> connectionFactory, Clock clock) {
        StreamingProperties.Redis redis = properties.redis();
        if (!redis.enabled() || connectionFactory.isEmpty()) {
            // No shared counter store, so no honest shared limit. A per-process bucket would only
            // bound one instance while reading as though it bounded the service.
            log.warn("Redis disabled: ingestion is NOT rate limited");
            return key -> RateLimitDecision.allowed(Long.MAX_VALUE);
        }
        log.info(
                "Ingestion rate limit: burst {}, sustained {}/hour per client",
                redis.rateLimit().capacity(),
                redis.rateLimit().refillPerHour());
        return new RedisTokenBucketRateLimiter(
                new StringRedisTemplate(connectionFactory.get()),
                tokenBucketScript(),
                clock,
                redis.rateLimit().capacity(),
                redis.rateLimit().refillPerHour());
    }

    /**
     * Spring executes this with {@code EVALSHA} and falls back to {@code EVAL} on {@code NOSCRIPT},
     * so the script is cached server-side without any manual bookkeeping.
     */
    private static RedisScript<List> tokenBucketScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/token-bucket.lua"));
        script.setResultType(List.class);
        return script;
    }
}
