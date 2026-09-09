package com.azt.streaming.ingestion.web.dto;

import com.azt.streaming.ingestion.domain.StreamJob;
import com.azt.streaming.shared.storage.CatalogEntry;
import com.azt.streaming.shared.storage.MediaStorage;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * One watchable video, for the library the player renders.
 *
 * <p>Separate from {@link StreamJobResponse} because it answers a different question. That one
 * reports how far an ingestion got and is null-heavy while it runs; this one only ever describes
 * media that exists, so it carries no status and no failure reason.
 *
 * <p>{@code title} is omitted rather than sent as null when the sidecar is missing, so a client can
 * tell "no name recorded" from "named the empty string".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VideoSummaryResponse(
        String videoId,
        String title,
        String streamUrl,
        String posterUrl,
        List<String> qualities,
        long sizeBytes,
        Instant readyAt) {

    public static VideoSummaryResponse from(CatalogEntry entry) {
        return new VideoSummaryResponse(
                entry.videoId(),
                entry.title(),
                StreamJob.streamUrlFor(entry.videoId()),
                entry.hasPoster() ? posterUrlFor(entry.videoId()) : null,
                entry.qualities(),
                entry.sizeBytes(),
                entry.readyAt());
    }

    /**
     * Built here rather than beside {@link StreamJob#streamUrlFor}, which exists because the job
     * record itself has to publish a stream URL. Nothing outside this listing ever names a poster.
     */
    private static String posterUrlFor(String videoId) {
        return "/api/v1/stream/" + videoId + "/" + MediaStorage.POSTER;
    }
}
