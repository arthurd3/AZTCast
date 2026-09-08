package com.azt.streaming.ingestion.domain;

import java.util.Optional;

/**
 * Remembers which magnet is already being ingested, so the same torrent is not fetched twice.
 *
 * <p>Note this is a registry, not a lock, and the difference matters. A lock with a lease is the
 * obvious reach here and it is wrong: ingestion returns in milliseconds while the work it guards
 * runs for hours, so any lease short enough to be safe expires long before the download and encode
 * finish. Binding the magnet to its {@code videoId} for the lifetime of the result is both correct
 * and more useful — the second caller gets the first caller's video instead of an error.
 */
public interface MagnetRegistry {

    /**
     * Claims {@code magnetUrl} for {@code videoId}.
     *
     * @return empty if the claim was won and ingestion should start; otherwise the {@code videoId}
     *     that already owns this magnet, which the caller should return instead
     */
    Optional<String> claim(String magnetUrl, String videoId);

    /** Releases a claim whose ingestion failed, so a retry is possible. */
    void release(String magnetUrl);
}
