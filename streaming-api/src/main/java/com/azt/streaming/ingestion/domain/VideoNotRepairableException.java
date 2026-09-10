package com.azt.streaming.ingestion.domain;

/**
 * Raised when a video is broken and there is nothing left to rebuild it from.
 *
 * <p>The one genuinely unrecoverable state: the HLS output has lost segments, the raw download has
 * been reaped, and the sidecar records no magnet — because the video was ingested before the magnet
 * was recorded at all. Nothing on this host knows where those bytes came from.
 *
 * <p>A plain {@code RuntimeException} in the slice that raises it, not in {@code shared.error}:
 * {@code ArchitectureTest.nothingDependsOnTheErrorBoundary} forbids a slice depending on the error
 * boundary, so the mapping to a status code lives one-way in {@code GlobalExceptionHandler}.
 */
public class VideoNotRepairableException extends RuntimeException {

    public VideoNotRepairableException(String videoId, String reason) {
        super("Cannot repair %s: %s".formatted(videoId, reason));
    }
}
