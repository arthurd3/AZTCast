package com.azt.streaming.ingestion.infrastructure;

import com.azt.streaming.ingestion.domain.StreamJob;
import com.azt.streaming.ingestion.domain.StreamJobRepository;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis as the source of truth, with a local mirror that keeps this instance working without it.
 *
 * <p>A bare Redis repository would turn an outage into a 500 on every status poll, which is a poor
 * trade for a service whose actual product — playing video — does not involve Redis at all. Here an
 * outage degrades ingestion tracking to exactly what it was before Redis existed.
 *
 * <p>Reads go to Redis <em>first</em> and fall back locally, never the other way round. Local-first
 * would be faster and wrong: with more than one instance, this process's copy of a job another
 * process has since advanced is stale, and preferring it would report obsolete progress.
 */
@Slf4j
public class ResilientStreamJobRepository implements StreamJobRepository {

    private final StreamJobRepository redis;
    private final StreamJobRepository local;

    public ResilientStreamJobRepository(StreamJobRepository redis, StreamJobRepository local) {
        this.redis = redis;
        this.local = local;
    }

    @Override
    public StreamJob save(StreamJob job) {
        local.save(job);
        try {
            redis.save(job);
        } catch (RuntimeException e) {
            // Losing durability is bad; losing the ingestion because durability is unavailable is
            // worse. The encode is already running and its output is the thing that matters.
            log.warn("Could not persist job {} to Redis; keeping it locally only", job.videoId(), e);
        }
        return job;
    }

    @Override
    public List<StreamJob> findUnfinished() {
        try {
            return redis.findUnfinished();
        } catch (RuntimeException e) {
            log.warn("Redis unavailable while listing unfinished jobs; using local state", e);
            return local.findUnfinished();
        }
    }

    @Override
    public Optional<StreamJob> findById(String videoId) {
        try {
            Optional<StreamJob> fromRedis = redis.findById(videoId);
            if (fromRedis.isPresent()) {
                return fromRedis;
            }
        } catch (RuntimeException e) {
            log.warn("Redis unavailable while reading job {}; falling back to local state", videoId, e);
        }
        return local.findById(videoId);
    }
}
