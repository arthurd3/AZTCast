package com.azt.streaming.shared.storage;

import java.time.Instant;
import java.util.List;

/**
 * One playable video, as the disk describes it.
 *
 * <p>{@code title} is null when the metadata sidecar is absent — which is the case for everything
 * transcoded before the sidecar existed, and for anything whose write failed. Callers render the id
 * instead; they must not treat a missing title as a missing video.
 *
 * @param readyAt when the master playlist was written, i.e. when the video became playable
 * @param qualities rendition names, highest first
 * @param sizeBytes the ladder's footprint on disk
 * @param hasPoster whether a poster frame was extracted; false for anything encoded before posters
 *     existed, and for sources ffmpeg could not read a frame out of
 */
public record CatalogEntry(
        String videoId,
        String title,
        Instant readyAt,
        List<String> qualities,
        long sizeBytes,
        boolean hasPoster) {}
