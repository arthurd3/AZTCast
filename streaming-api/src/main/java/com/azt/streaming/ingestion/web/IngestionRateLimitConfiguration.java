package com.azt.streaming.ingestion.web;

import com.azt.streaming.shared.ratelimit.RateLimitInterceptor;
import com.azt.streaming.shared.ratelimit.RateLimiter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Decides <em>which</em> routes are rate limited. The mechanism lives in {@code shared.ratelimit};
 * the policy belongs here, next to the endpoints it protects, so changing a URL does not mean
 * editing a shared package.
 *
 * <p>Only ingestion is limited. It is unauthenticated and its side effect is making the server
 * download an arbitrary torrent, which is the one place on this API where an unbounded caller is a
 * real liability. Playback is deliberately excluded: a single player pulls dozens of segments a
 * minute, so limiting it would throttle ordinary viewing.
 */
@Configuration
public class IngestionRateLimitConfiguration implements WebMvcConfigurer {

    private final ObjectProvider<RateLimiter> rateLimiter;

    public IngestionRateLimitConfiguration(ObjectProvider<RateLimiter> rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // ObjectProvider, not a hard dependency: @WebMvcTest slices instantiate WebMvcConfigurers
        // but not arbitrary @Configuration beans, so requiring a RateLimiter here would break every
        // controller slice test that does not opt into the whole Redis layer.
        RateLimiter limiter = rateLimiter.getIfAvailable();
        if (limiter == null) {
            return;
        }
        registry.addInterceptor(new RateLimitInterceptor(limiter))
                .addPathPatterns("/api/v1/videos", "/api/v1/video/download", "/api/v1/videos/*/repair");
    }
}
