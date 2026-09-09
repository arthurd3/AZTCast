package com.azt.streaming.ingestion.infrastructure;

import com.azt.streaming.ingestion.domain.StreamJob;
import com.azt.streaming.ingestion.domain.StreamJobRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;

/**
 * Job state in Redis, so it survives a restart and is visible to every instance.
 *
 * <p>This is the one-class swap ADR-0003 anticipated. Note what it does <em>not</em> change: the
 * media is still the durable artefact on disk, and a job record is still only a progress signal
 * about it — which is why every key has a TTL rather than living forever.
 */
public class RedisStreamJobRepository implements StreamJobRepository {

    static final String KEY_PREFIX = "aztcast:v1:job:";

    private final RedisTemplate<String, StreamJob> template;
    private final Duration ttl;

    public RedisStreamJobRepository(RedisTemplate<String, StreamJob> template, Duration ttl) {
        this.template = template;
        this.ttl = ttl;
    }

    @Override
    public StreamJob save(StreamJob job) {
        // Sliding, not fixed: the TTL is refreshed on each transition, so a long download cannot
        // expire its own job record while it is still making progress.
        template.opsForValue().set(key(job.videoId()), job, ttl);
        return job;
    }

    @Override
    public Optional<StreamJob> findById(String videoId) {
        return Optional.ofNullable(template.opsForValue().get(key(videoId)));
    }

    @Override
    public List<StreamJob> findUnfinished() {
        List<StreamJob> unfinished = new ArrayList<>();
        // SCAN, not KEYS. KEYS is O(N) over the whole keyspace and blocks the single-threaded
        // server for the duration — on a shared Redis that is everyone's outage, not just ours.
        // SCAN spreads the same work over many small calls.
        try (Cursor<String> cursor =
                template.scan(ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(256).build())) {
            while (cursor.hasNext()) {
                StreamJob job = template.opsForValue().get(cursor.next());
                if (job != null && job.inFlight()) {
                    unfinished.add(job);
                }
            }
        }
        return unfinished;
    }

    private static String key(String videoId) {
        return KEY_PREFIX + videoId;
    }
}
