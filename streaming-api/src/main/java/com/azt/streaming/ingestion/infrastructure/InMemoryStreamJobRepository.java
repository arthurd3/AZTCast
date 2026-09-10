package com.azt.streaming.ingestion.infrastructure;

import com.azt.streaming.ingestion.domain.StreamJob;
import com.azt.streaming.ingestion.domain.StreamJobRepository;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link StreamJobRepository}.
 *
 * <p>Job state does not survive a restart and is not visible to another instance. That was the whole
 * story before Redis (ADR-0003); it is now the fallback, used when Redis is disabled and as the
 * local tier of {@link ResilientStreamJobRepository} when it is enabled but unreachable.
 *
 * <p>The map is bounded. Unbounded it was a slow leak — nothing ever removed a finished job — which
 * only stayed invisible because the process was restarted often enough.
 */
public class InMemoryStreamJobRepository implements StreamJobRepository {

    /**
     * Ceiling on retained jobs. Generous enough that no realistic backlog is evicted while it
     * matters, small enough that the map cannot grow without limit in a long-lived process.
     */
    static final int MAX_JOBS = 10_000;

    private final Map<String, StreamJob> jobs = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, StreamJob> eldest) {
                    return size() > MAX_JOBS;
                }
            });

    @Override
    public StreamJob save(StreamJob job) {
        jobs.put(job.videoId(), job);
        return job;
    }

    @Override
    public Optional<StreamJob> findById(String videoId) {
        return Optional.ofNullable(jobs.get(videoId));
    }

    @Override
    public void delete(String videoId) {
        jobs.remove(videoId);
    }

    @Override
    public List<StreamJob> findUnfinished() {
        // Copy under the monitor: the map is synchronized per operation, but iterating it is not.
        synchronized (jobs) {
            return jobs.values().stream().filter(StreamJob::inFlight).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        }
    }
}
