package com.azt.streaming.ingestion.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

import com.azt.streaming.ingestion.domain.StreamJob;
import com.azt.streaming.ingestion.domain.StreamJobRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

@ExtendWith(MockitoExtension.class)
class ResilientStreamJobRepositoryTest {

    private static final StreamJob JOB = StreamJob.downloading("vid", "magnet:?xt=urn:btih:x", Instant.EPOCH);

    @Mock private StreamJobRepository redis;

    private final StreamJobRepository local = new InMemoryStreamJobRepository();

    private StreamJobRepository repository() {
        return new ResilientStreamJobRepository(redis, local);
    }

    @Test
    @DisplayName("a Redis outage does not fail an ingestion that is already running")
    void savesLocallyWhenRedisIsDown() {
        willThrow(new RedisConnectionFailureException("down")).given(redis).save(any());

        assertThat(repository().save(JOB)).isEqualTo(JOB);
        assertThat(local.findById("vid")).contains(JOB);
    }

    @Test
    void readsThroughToLocalStateWhenRedisIsDown() {
        local.save(JOB);
        given(redis.findById("vid")).willThrow(new RedisConnectionFailureException("down"));

        assertThat(repository().findById("vid")).contains(JOB);
    }

    @Test
    @DisplayName("Redis wins a disagreement, because another instance may have advanced the job")
    void prefersRedisOverTheLocalMirror() {
        // Local-first would be faster and wrong: with more than one instance, this process's copy
        // of a job that another process has since moved on is simply stale.
        StreamJob advanced = JOB.ready(Instant.EPOCH.plusSeconds(60));
        local.save(JOB);
        given(redis.findById("vid")).willReturn(Optional.of(advanced));

        assertThat(repository().findById("vid")).contains(advanced);
    }

    @Test
    void reportsMissingWhenNeitherTierHasIt() {
        given(redis.findById("nope")).willReturn(Optional.empty());

        assertThat(repository().findById("nope")).isEmpty();
    }
}
