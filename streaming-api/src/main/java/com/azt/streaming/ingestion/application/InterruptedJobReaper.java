package com.azt.streaming.ingestion.application;

import com.azt.streaming.ingestion.domain.MagnetRegistry;
import com.azt.streaming.ingestion.domain.StreamJob;
import com.azt.streaming.ingestion.domain.StreamJobRepository;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Tells the truth about jobs that a restart interrupted.
 *
 * <p>Durable job state introduced a failure mode that in-memory state could not have. Ingestion runs
 * on an executor, not in a persistent queue, so a restart abandons the work — but the record of it
 * now survives. Left alone, a job written before the restart reports DOWNLOADING for the seven days
 * its key lives, and a caller polling it waits forever for a download that stopped.
 *
 * <p>That is the exact complaint ADR-0003 was written to fix — "a caller could not distinguish still
 * transcoding from failed twenty minutes ago" — reappearing in a new form, so it is fixed here
 * rather than documented as a quirk. The magnet claim is released too, or the video could never be
 * re-ingested.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterruptedJobReaper {

    private final StreamJobRepository jobRepository;
    private final MagnetRegistry magnetRegistry;
    private final Clock clock;

    /**
     * Runs once the context is ready.
     *
     * <p>Safe precisely because nothing resumes: any job still marked in-flight at startup belongs
     * to a process that is gone. If work ever does become resumable, this has to become "resume or
     * fail", not "fail" — which is why the reasoning is written down rather than assumed.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void failJobsInterruptedByRestart() {
        List<StreamJob> interrupted = jobRepository.findUnfinished();
        if (interrupted.isEmpty()) {
            return;
        }
        log.warn("Failing {} job(s) left in flight by a previous run", interrupted.size());
        for (StreamJob job : interrupted) {
            jobRepository.save(job.failed("Interrupted by a service restart; start the ingestion again.", clock.instant()));
            magnetRegistry.release(job.magnetUrl());
        }
    }
}
