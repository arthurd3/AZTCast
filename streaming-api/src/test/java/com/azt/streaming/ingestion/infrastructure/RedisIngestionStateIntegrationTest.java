package com.azt.streaming.ingestion.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.azt.streaming.ingestion.domain.MagnetRegistry;
import com.azt.streaming.ingestion.domain.StreamJob;
import com.azt.streaming.ingestion.domain.StreamJobRepository;
import com.azt.streaming.ingestion.domain.StreamJobStatus;
import com.azt.streaming.shared.ratelimit.RateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The parts of the Redis layer that are only worth testing against a real Redis: that state
 * genuinely outlives the objects holding it, that {@code SET NX} actually settles a race, and that
 * the Lua token bucket's arithmetic is right.
 *
 * <p>{@code disabledWithoutDocker} is what keeps {@code mvn verify} green on a machine with no
 * Docker — the whole suite still runs, this class is skipped rather than failed.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "aztcast.streaming.redis.enabled=true",
            "aztcast.streaming.redis.rate-limit.capacity=3",
            // 3600/hour = one token per second, so the refill is observable without sleeping.
            "aztcast.streaming.redis.rate-limit.refill-per-hour=3600"
        })
class RedisIngestionStateIntegrationTest {

    @Container
    @ServiceConnection
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Autowired private StreamJobRepository jobRepository;
    @Autowired private RedisConnectionFactory connectionFactory;
    @Autowired private MagnetRegistry magnetRegistry;
    @Autowired private RateLimiter rateLimiter;

    @Test
    @DisplayName("job state outlives the process that wrote it")
    void jobStateSurvivesARestart() {
        String videoId = UUID.randomUUID().toString();
        jobRepository.save(StreamJob.downloading(videoId, "magnet:?xt=urn:btih:" + videoId, Instant.EPOCH)
                .ready(Instant.EPOCH.plusSeconds(30)));

        // A brand-new repository over the same Redis, sharing no memory with the one that wrote
        // the job — which is what a restarted instance is, and the property ADR-0003 lacked. Reading
        // back through the same bean would prove nothing, because the in-memory tier would answer.
        StreamJobRepository afterRestart = new RedisStreamJobRepository(
                freshTemplate(connectionFactory), java.time.Duration.ofDays(7));

        assertThat(afterRestart.findById(videoId))
                .hasValueSatisfying(job -> {
                    assertThat(job.status()).isEqualTo(StreamJobStatus.READY);
                    assertThat(job.streamUrl()).isEqualTo("/api/v1/stream/" + videoId + "/master.m3u8");
                });
    }

    @Test
    @DisplayName("the second caller for a magnet gets the first caller's video")
    void deduplicatesByInfohashAcrossDifferentMagnetSpellings() {
        String hash = "0123456789abcdef0123456789abcdef0123beef";
        String first = "magnet:?xt=urn:btih:" + hash + "&dn=A&tr=udp://one:1337";
        String second = "magnet:?xt=urn:btih:" + hash.toUpperCase() + "&tr=udp://two:80";

        assertThat(magnetRegistry.claim(first, "video-one")).isEmpty();

        // Different string, same torrent — and hours of download and encode avoided.
        assertThat(magnetRegistry.claim(second, "video-two")).contains("video-one");
    }

    @Test
    void releasingAClaimAllowsARetry() {
        String magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef0123cafe";
        magnetRegistry.claim(magnet, "first-attempt");

        magnetRegistry.release(magnet);

        assertThat(magnetRegistry.claim(magnet, "second-attempt")).isEmpty();
    }

    @Test
    @DisplayName("the token bucket allows a burst up to capacity, then denies")
    void enforcesTheConfiguredRate() {
        String client = "203.0.113." + (int) (Math.abs(UUID.randomUUID().getLeastSignificantBits()) % 250);

        for (int i = 1; i <= 3; i++) {
            assertThat(rateLimiter.tryConsume(client).allowed())
                    .as("request %d is within the burst of 3", i)
                    .isTrue();
        }

        var denied = rateLimiter.tryConsume(client);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfter()).isPositive();
    }

    @Test
    void countsEachClientSeparately() {
        assertThat(rateLimiter.tryConsume("198.51.100.1").allowed()).isTrue();
        assertThat(rateLimiter.tryConsume("198.51.100.2").allowed()).isTrue();
    }

    /** Mirrors the production serializer setup, independently of the bean under test. */
    private static RedisTemplate<String, StreamJob> freshTemplate(RedisConnectionFactory connectionFactory) {
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
