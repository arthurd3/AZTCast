package com.azt.streaming.ingestion.domain;

import java.time.Instant;

/**
 * One magnet's journey to a playable stream.
 *
 * <p>Nothing tracked this before: the API handed back a videoId and forgot it existed, so a caller
 * had no way to tell "still transcoding" from "failed twenty minutes ago" — both looked like a 404
 * on the master playlist, and the only strategy was to keep retrying forever.
 */
public record StreamJob(
        String videoId,
        StreamJobStatus status,
        String magnetUrl,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public static StreamJob downloading(String videoId, String magnetUrl, Instant now) {
        return new StreamJob(videoId, StreamJobStatus.DOWNLOADING, magnetUrl, null, now, now);
    }

    public StreamJob transcoding(Instant now) {
        return new StreamJob(videoId, StreamJobStatus.TRANSCODING, magnetUrl, null, createdAt, now);
    }

    public StreamJob ready(Instant now) {
        return new StreamJob(videoId, StreamJobStatus.READY, magnetUrl, null, createdAt, now);
    }

    public StreamJob failed(String reason, Instant now) {
        return new StreamJob(videoId, StreamJobStatus.FAILED, magnetUrl, reason, createdAt, now);
    }

    /** Path to the master playlist, or null while it does not exist yet. */
    public String streamUrl() {
        return status == StreamJobStatus.READY ? "/api/v1/stream/" + videoId + "/master.m3u8" : null;
    }
}
