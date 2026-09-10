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
 *
 * <p>{@code transcodePercent} is absent rather than zero until the encode starts — the record is
 * {@code NON_NULL}, so a client can tell "not started" from "0% done" without a second field to say
 * so.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreamJobResponse(
        String videoId,
        StreamJobStatus status,
        int progressPercent,
        Integer transcodePercent,
        String streamUrl,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public static StreamJobResponse from(StreamJob job) {
        return new StreamJobResponse(
                job.videoId(),
                job.status(),
                job.progressPercent(),
                job.transcodePercent(),
                job.streamUrl(),
                job.failureReason(),
                job.createdAt(),
                job.updatedAt());
    }
}
