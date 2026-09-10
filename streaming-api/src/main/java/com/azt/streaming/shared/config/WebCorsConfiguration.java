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
 * on :8080. nginx removes that in production: it serves the player and proxies {@code /api} on one
 * origin, so the browser makes a genuinely same-origin request and the production list is empty —
 * which switches this configuration off entirely.
 *
 * <p>Development is <em>not</em> same-origin as far as this application is concerned, and that is
 * why the local profile has to name an origin. Vite proxies with {@code changeOrigin: true}, which
 * rewrites {@code Host} to the backend but forwards the browser's {@code Origin} untouched — so
 * Spring compares {@code localhost:8080} against {@code http://localhost:5173}, decides the request
 * is cross-origin, and applies this policy to it.
 *
 * <p>Which is why {@code POST} is here. Listing and playback are reads, but ingestion is a
 * {@code POST}, and leaving it out meant Spring answered every submitted magnet with a bare
 * {@code 403 Invalid CORS request} — no problem document, because the rejection happens in
 * {@code DefaultCorsProcessor} long before any handler runs. Submitting a torrent from the player
 * was impossible under the profile the player is developed against.
 *
 * <p>{@code PUT} and {@code DELETE} joined them when videos became keepable. The failure was
 * identical and just as invisible from the server side: the save button on a library card produced
 * a bare 403 with nothing in the log, because the request never reached a controller. This list
 * has to grow whenever a mutating endpoint is added, and there is nothing that checks it —
 * {@code WebCorsConfigurationTest} exists to be that check.
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
                .allowedMethods("GET", "HEAD", "OPTIONS", "POST", "PUT", "DELETE")
                .allowedHeaders("*")
                .allowCredentials(false);
    }
}
