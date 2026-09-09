package com.azt.streaming.ingestion.domain;

import java.util.List;
import java.util.Optional;

/** Stores the state of in-flight and finished ingestions. */
public interface StreamJobRepository {

    StreamJob save(StreamJob job);

    Optional<StreamJob> findById(String videoId);

    /**
     * Jobs still claiming to be DOWNLOADING or TRANSCODING.
     *
     * <p>Exists for one reason: durable job state introduced a failure mode that ephemeral state did
     * not have. Work does not resume across a restart, so a job written before one would otherwise
     * claim to be downloading for as long as its record survives — permanently, from the caller's
     * point of view. Finding those is what lets startup tell the truth about them.
     */
    List<StreamJob> findUnfinished();
}
