package com.azt.streaming.shared.config;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS, driven by configuration instead of a wildcard.
 *
 * <p>This replaces {@code allowedOriginPatterns("*")} on {@code /**} — shipped with the comment
 * "Allow all origins for testing" — which also advertised PUT and DELETE that no endpoint
 * implements.
 *
 * <p>The wildcard existed because the player was served by Live Server on :5501 while the API ran
 * on :8080. Both deployment topologies now avoid that: Vite proxies {@code /api} in development and
 * nginx does the same in production, so the browser only ever makes same-origin requests and the
 * production origin list is empty. Only the safe read methods are exposed; playback is all GET.
 */
@Configuration
public class WebCorsConfiguration implements WebMvcConfigurer {

    private final List<String> allowedOrigins;

    public WebCorsConfiguration(StreamingProperties properties) {
        this.allowedOrigins = properties.web().allowedOrigins();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins.isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "HEAD", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false);
    }
}
