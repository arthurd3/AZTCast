package com.azt.streaming.ingestion.infrastructure;

import com.azt.streaming.ingestion.domain.StreamJob;
import com.azt.streaming.ingestion.domain.StreamJobRepository;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * In-memory {@link StreamJobRepository}.
 *
 * <p>Job state does not survive a restart, which is honest about what this service currently is: the
 * media itself lives on the filesystem and is the durable artefact, while job state is a
 * best-effort progress signal. Swapping in a persistent implementation is a one-class change
 * precisely because the port exists — see docs/decisions/0003.
 */
@Repository
public class InMemoryStreamJobRepository implements StreamJobRepository {

    private final Map<String, StreamJob> jobs = new ConcurrentHashMap<>();

    @Override
    public StreamJob save(StreamJob job) {
        jobs.put(job.videoId(), job);
        return job;
    }

    @Override
    public Optional<StreamJob> findById(String videoId) {
        return Optional.ofNullable(jobs.get(videoId));
    }
}
