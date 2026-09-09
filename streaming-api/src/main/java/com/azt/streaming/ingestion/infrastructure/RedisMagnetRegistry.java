package com.azt.streaming.ingestion.infrastructure;

import com.azt.streaming.ingestion.domain.MagnetRegistry;
import com.azt.streaming.ingestion.domain.MagnetUri;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link MagnetRegistry} backed by a single {@code SET NX} per magnet.
 *
 * <p>{@code SET key videoId NX EX ttl} is the whole mechanism: the first caller writes the key and
 * starts work, and every later caller reads back the {@code videoId} already stored there. It is
 * atomic in one round trip, needs no Lua, and cannot leave a stale lock behind, because the key is
 * the answer rather than a token.
 */
@Slf4j
public class RedisMagnetRegistry implements MagnetRegistry {

    static final String KEY_PREFIX = "aztcast:v1:magnet:";

    private final StringRedisTemplate template;
    private final Duration ttl;

    public RedisMagnetRegistry(StringRedisTemplate template, Duration ttl) {
        this.template = template;
        this.ttl = ttl;
    }

    @Override
    public Optional<String> claim(String magnetUrl, String videoId) {
        String key = key(magnetUrl);
        try {
            if (Boolean.TRUE.equals(template.opsForValue().setIfAbsent(key, videoId, ttl))) {
                return Optional.empty();
            }
            // Lost the race, or this magnet was ingested earlier. Either way the caller wants the
            // videoId that owns it. A null here means the key expired in the microseconds between
            // the two calls; treating that as "go ahead" is the right outcome.
            return Optional.ofNullable(template.opsForValue().get(key));
        } catch (RuntimeException e) {
            // Fail open: Redis being down must not stop someone ingesting a video. The cost is a
            // possible duplicate download, which is wasteful, not broken.
            log.warn("Redis unavailable for magnet claim; proceeding without deduplication", e);
            return Optional.empty();
        }
    }

    @Override
    public void release(String magnetUrl) {
        try {
            template.delete(key(magnetUrl));
        } catch (RuntimeException e) {
            log.warn("Could not release magnet claim; it will expire on its own", e);
        }
    }

    private static String key(String magnetUrl) {
        return KEY_PREFIX + MagnetUri.identity(magnetUrl);
    }
}
