package com.azt.streaming.ingestion.web.dto;

import com.azt.streaming.ingestion.domain.StreamJob;
import com.azt.streaming.ingestion.domain.StreamJobStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * What a client gets back for an ingestion.
 *
 * <p>The legacy endpoint returned the videoId embedded in a Portuguese sentence, so callers had to
 * regex it out of prose. It is a field.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreamJobResponse(
        String videoId,
        StreamJobStatus status,
        String streamUrl,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public static StreamJobResponse from(StreamJob job) {
        return new StreamJobResponse(
                job.videoId(),
                job.status(),
                job.streamUrl(),
                job.failureReason(),
                job.createdAt(),
                job.updatedAt());
    }
}
