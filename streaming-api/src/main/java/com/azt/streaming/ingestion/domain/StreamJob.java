package com.azt.streaming.ingestion.domain;

import java.time.Instant;

/**
 * One magnet's journey to a playable stream.
 *
 * <p>Nothing tracked this before: the API handed back a videoId and forgot it existed, so a caller
 * had no way to tell "still transcoding" from "failed twenty minutes ago" — both looked like a 404
 * on the master playlist, and the only strategy was to keep retrying forever.
 *
 * @param progressPercent how much of the <em>torrent</em> has arrived, 0-100. Deliberately the
 *     download stage only: the swarm reports pieces, so this figure is measured, while ffmpeg
 *     reports nothing this pipeline reads — and an invented bar that stalls is worse than no bar. It
 *     reaches 100 when the download finishes and stays there, which is why a failed job still says
 *     how far it got. A plain record component rather than a derived {@code getX()}, because
 *     components are the whole wire format here — see {@link #inFlight()}.
 */
public record StreamJob(
        String videoId,
        StreamJobStatus status,
        int progressPercent,
        String magnetUrl,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public static StreamJob downloading(String videoId, String magnetUrl, Instant now) {
        return new StreamJob(videoId, StreamJobStatus.DOWNLOADING, 0, magnetUrl, null, now, now);
    }

    /** The same job, further along. Clamped, because a percentage outside 0-100 is a bug elsewhere. */
    public StreamJob withProgress(int percent, Instant now) {
        int clamped = Math.max(0, Math.min(100, percent));
        return new StreamJob(videoId, status, clamped, magnetUrl, failureReason, createdAt, now);
    }

    public StreamJob transcoding(Instant now) {
        return new StreamJob(videoId, StreamJobStatus.TRANSCODING, 100, magnetUrl, null, createdAt, now);
    }

    public StreamJob ready(Instant now) {
        return new StreamJob(videoId, StreamJobStatus.READY, 100, magnetUrl, null, createdAt, now);
    }

    public StreamJob failed(String reason, Instant now) {
        return new StreamJob(videoId, StreamJobStatus.FAILED, progressPercent, magnetUrl, reason, createdAt, now);
    }

    /**
     * Whether this job claims work is still happening.
     *
     * <p>Named {@code inFlight()} rather than {@code isInFlight()} deliberately. Jackson picks up
     * {@code isX()} methods on a record as extra properties, so the getter-shaped name serialised an
     * {@code "inFlight"} field into Redis that deserialisation then rejected as unknown. Every other
     * derived accessor here ({@code streamUrl()}) already avoids the getter prefix; this one now
     * matches, and the record's components stay the whole wire format.
     */
    public boolean inFlight() {
        return status == StreamJobStatus.DOWNLOADING || status == StreamJobStatus.TRANSCODING;
    }

    /** Path to the master playlist, or null while it does not exist yet. */
    public String streamUrl() {
        return status == StreamJobStatus.READY ? streamUrlFor(videoId) : null;
    }

    /**
     * Path to {@code videoId}'s master playlist, whether or not a job for it still exists.
     *
     * <p>Static, so the catalogue can name a video whose job record expired long ago. Also static so
     * Jackson ignores it: an instance method shaped like a getter would become an eighth property and
     * break deserialisation, which is the same trap {@link #inFlight()} is named around.
     */
    public static String streamUrlFor(String videoId) {
        return "/api/v1/stream/" + videoId + "/master.m3u8";
    }
}
