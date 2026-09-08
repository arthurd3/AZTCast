package com.azt.streaming.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.azt.streaming.ingestion.domain.MagnetRegistry;
import com.azt.streaming.ingestion.domain.StreamJob;
import com.azt.streaming.ingestion.domain.StreamJobRepository;
import com.azt.streaming.ingestion.domain.StreamJobStatus;
import com.azt.streaming.ingestion.infrastructure.InMemoryStreamJobRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InterruptedJobReaperTest {

    private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");

    private final StreamJobRepository repository = new InMemoryStreamJobRepository();
    private final List<String> released = new ArrayList<>();

    private final MagnetRegistry registry = new MagnetRegistry() {
        @Override
        public Optional<String> claim(String magnetUrl, String videoId) {
            return Optional.empty();
        }

        @Override
        public void release(String magnetUrl) {
            released.add(magnetUrl);
        }
    };

    private void reap() {
        new InterruptedJobReaper(repository, registry, Clock.fixed(NOW, ZoneOffset.UTC))
                .failJobsInterruptedByRestart();
    }

    @Test
    @DisplayName("a job the restart abandoned stops claiming to be in progress")
    void failsJobsLeftInFlight() {
        // Without this, durable state means a caller polls a DOWNLOADING job forever, because the
        // process that was doing the downloading no longer exists.
        repository.save(StreamJob.downloading("a", "magnet:a", NOW));
        repository.save(StreamJob.downloading("b", "magnet:b", NOW).transcoding(NOW));

        reap();

        assertThat(repository.findById("a")).hasValueSatisfying(job -> {
            assertThat(job.status()).isEqualTo(StreamJobStatus.FAILED);
            assertThat(job.failureReason()).contains("restart");
        });
        assertThat(repository.findById("b")).hasValueSatisfying(job -> assertThat(job.status())
                .isEqualTo(StreamJobStatus.FAILED));
    }

    @Test
    void leavesFinishedJobsAlone() {
        repository.save(StreamJob.downloading("done", "magnet:done", NOW).ready(NOW));
        repository.save(StreamJob.downloading("bad", "magnet:bad", NOW).failed("no seeders", NOW));

        reap();

        assertThat(repository.findById("done").orElseThrow().status()).isEqualTo(StreamJobStatus.READY);
        assertThat(repository.findById("bad").orElseThrow().failureReason()).isEqualTo("no seeders");
    }

    @Test
    @DisplayName("releases the magnet claim, or the video could never be re-ingested")
    void releasesTheClaimsOfFailedJobs() {
        repository.save(StreamJob.downloading("a", "magnet:a", NOW));

        reap();

        assertThat(released).containsExactly("magnet:a");
    }

    @Test
    void doesNothingWhenThereIsNothingToReap() {
        reap();

        assertThat(released).isEmpty();
    }
}
