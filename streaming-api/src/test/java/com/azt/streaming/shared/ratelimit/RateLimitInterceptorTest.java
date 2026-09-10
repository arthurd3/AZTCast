package com.azt.streaming.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitInterceptorTest {

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void letsAnAllowedRequestThroughAndReportsTheRemainingBudget() {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(key -> RateLimitDecision.allowed(4));

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("4");
    }

    @Test
    @DisplayName("throws rather than writing the response itself, so the error boundary shapes it")
    void throwsWhenDenied() {
        // This is what keeps a 429 in the same application/problem+json shape as every other
        // failure without the rate limiter depending on the error package.
        RateLimitInterceptor interceptor =
                new RateLimitInterceptor(key -> RateLimitDecision.denied(Duration.ofSeconds(42)));

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOf(RateLimitExceededException.class)
                .extracting(e -> ((RateLimitExceededException) e).retryAfter())
                .isEqualTo(Duration.ofSeconds(42));
    }

    @Test
    @DisplayName("a safe method spends no tokens, so listing the library cannot throttle itself out")
    void doesNotLimitSafeMethods() {
        // POST and GET share /api/v1/videos and the registry matches on path alone. Without this,
        // the player's own video list would spend the ingestion budget and start answering 429 after
        // five page loads.
        request.setMethod("GET");
        RateLimitInterceptor interceptor =
                new RateLimitInterceptor(key -> RateLimitDecision.denied(Duration.ofSeconds(42)));

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(response.getHeader("X-RateLimit-Remaining")).isNull();
    }

    @Test
    void stillLimitsUnsafeMethods() {
        request.setMethod("POST");

        assertThatThrownBy(
                        () -> new RateLimitInterceptor(key -> RateLimitDecision.denied(Duration.ofSeconds(1)))
                                .preHandle(request, response, new Object()))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void bucketsByClientAddress() {
        request.setRemoteAddr("203.0.113.7");
        StringBuilder seen = new StringBuilder();

        new RateLimitInterceptor(key -> {
                    seen.append(key);
                    return RateLimitDecision.allowed(1);
                })
                .preHandle(request, response, new Object());

        assertThat(seen.toString()).isEqualTo("203.0.113.7");
    }
}
