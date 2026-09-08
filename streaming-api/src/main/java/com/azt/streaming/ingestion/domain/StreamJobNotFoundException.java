package com.azt.streaming.ingestion.domain;

/** Raised when no ingestion is known for the requested id. */
public class StreamJobNotFoundException extends RuntimeException {

    public StreamJobNotFoundException(String videoId) {
        super("No ingestion job for video " + videoId);
    }
}
