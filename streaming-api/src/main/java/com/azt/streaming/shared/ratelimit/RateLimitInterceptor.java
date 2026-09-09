package com.azt.streaming.shared.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies a {@link RateLimiter} to the handlers it is registered for.
 *
 * <p>An interceptor rather than a servlet filter, and the reason is not style. A filter runs outside
 * {@code DispatcherServlet}, so an exception thrown from it never reaches
 * {@code @RestControllerAdvice} — the 429 would come back as the container's HTML error page while
 * every other failure in this API is {@code application/problem+json}. {@code preHandle} runs inside
 * the dispatch, so the throw becomes a normal handled exception.
 *
 * <p>It also runs before argument resolution, so a throttled request is rejected without parsing or
 * validating its body.
 *
 * <p>Not a {@code @Component}: it is constructed by the configuration that decides which routes it
 * guards. Component-scanning it would also register it into every {@code @WebMvcTest} slice, which
 * auto-includes {@code HandlerInterceptor} beans.
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;

    public RateLimitInterceptor(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String client = request.getRemoteAddr();
        RateLimitDecision decision = rateLimiter.tryConsume(client);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(client, decision.retryAfter());
        }
        response.setHeader("X-RateLimit-Remaining", Long.toString(decision.remaining()));
        return true;
    }
}
