package com.azt.streaming.shared.config;

import io.lettuce.core.ClientOptions;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Client-level Redis wiring. Knows about connections and timeouts, not about any feature.
 *
 * <p>The condition is spelled as a string because {@code @Conditional} is evaluated before
 * {@code @ConfigurationProperties} binding, so it cannot read {@link StreamingProperties}. It is
 * kept to whole configuration classes for that reason — every actual <em>value</em> still comes
 * from the bound record.
 */
@Configuration
@ConditionalOnProperty(prefix = "aztcast.streaming.redis", name = "enabled", havingValue = "true")
public class RedisConfiguration {

    /**
     * The single most important setting here.
     *
     * <p>Lettuce's default disconnected behaviour is to <em>queue</em> commands while the connection
     * is down and fail them only at the command timeout. With request threads issuing those
     * commands, a Redis outage becomes a Tomcat thread-pool exhaustion — the cache takes the
     * application down with it. {@code REJECT_COMMANDS} fails immediately instead, which is what
     * lets every caller here degrade rather than block.
     */
    @Bean
    LettuceClientConfigurationBuilderCustomizer lettuceFailsFast() {
        return builder -> builder.clientOptions(ClientOptions.builder()
                        .autoReconnect(true)
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .build())
                .commandTimeout(Duration.ofMillis(250));
    }
}
