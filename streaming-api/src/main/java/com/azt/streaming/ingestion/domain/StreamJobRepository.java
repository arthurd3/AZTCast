package com.azt.streaming.ingestion.domain;

import java.util.Optional;

/** Stores the state of in-flight and finished ingestions. */
public interface StreamJobRepository {

    StreamJob save(StreamJob job);

    Optional<StreamJob> findById(String videoId);
}
