package com.azt.streaming.ingestion.infrastructure;

import com.azt.streaming.ingestion.domain.MagnetRegistry;
import com.azt.streaming.ingestion.domain.StreamJob;
import com.azt.streaming.ingestion.domain.StreamJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.azt.streaming.shared.config.StreamingProperties;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Chooses the ingestion persistence implementations from the bound configuration.
 *
 * <p>A factory rather than {@code @ConditionalOnProperty} on each class: conditions are evaluated
 * before {@code @ConfigurationProperties} binding, so they can only match on a property name spelled
 * as a string that no compiler checks. Here the switch reads the same validated record as everything
 * else, and the wiring is visible in one place instead of spread across four annotations.
 */
@Slf4j
@Configuration
public class IngestionPersistenceConfiguration {

    @Bean
    StreamJobRepository streamJobRepository(
            StreamingProperties properties, Optional<RedisConnectionFactory> connectionFactory) {
        InMemoryStreamJobRepository local = new InMemoryStreamJobRepository();
        if (!properties.redis().enabled() || connectionFactory.isEmpty()) {
            log.info("Redis disabled: job state is in-memory and will not survive a restart");
            return local;
        }
        log.info("Redis enabled: job state persists for {}", properties.redis().jobTtl());
        return new ResilientStreamJobRepository(
                new RedisStreamJobRepository(
                        streamJobTemplate(connectionFactory.get()), properties.redis().jobTtl()),
                local);
    }

    @Bean
    MagnetRegistry magnetRegistry(StreamingProperties properties, Optional<RedisConnectionFactory> connectionFactory) {
        if (!properties.redis().enabled() || connectionFactory.isEmpty()) {
            // Every claim wins. Deliberately not an in-process map: deduplication only means
            // anything across restarts and instances, and a per-process one would catch a
            // double-clicked button while implying a guarantee it cannot make.
            return new MagnetRegistry() {
                @Override
                public Optional<String> claim(String magnetUrl, String videoId) {
                    return Optional.empty();
                }

                @Override
                public void release(String magnetUrl) {
                    // Nothing was claimed.
                }
            };
        }
        return new RedisMagnetRegistry(
                new StringRedisTemplate(connectionFactory.get()), properties.redis().jobTtl());
    }

    /**
     * Typed to {@link StreamJob}, so the stored JSON carries no {@code @class} field and there is no
     * polymorphic deserialisation to get wrong. {@code JavaTimeModule} is required or the record's
     * {@code Instant}s fail to serialise; ISO strings rather than epoch numbers so a human reading
     * the key with {@code redis-cli} can tell what it says.
     */
    private static RedisTemplate<String, StreamJob> streamJobTemplate(RedisConnectionFactory connectionFactory) {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        RedisTemplate<String, StreamJob> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new Jackson2JsonRedisSerializer<>(objectMapper, StreamJob.class));
        template.afterPropertiesSet();
        return template;
    }
}
