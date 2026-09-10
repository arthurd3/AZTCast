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

    /**
     * Forgets the job for {@code videoId}, if there is one.
     *
     * <p>Exists because deleting a video has to take its job record with it. Job records expire on a
     * TTL, and the media used to expire on a window chosen to match — two durations kept in step so
     * that neither outlived the other. The ladder is permanent now, so that pairing is gone and this
     * takes its place: the one moment media disappears is the one moment the job describing it
     * should, and doing both at once is exact where two clocks were only approximate.
     *
     * <p>Idempotent. A job that was never there, or that expired on its own, is not an error.
     */
    void delete(String videoId);
}
