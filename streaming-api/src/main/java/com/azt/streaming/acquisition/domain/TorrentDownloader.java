package com.azt.streaming.acquisition.domain;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;

/**
 * Stage one of the pipeline: turn a magnet URI into a playable file on disk.
 *
 * <p>This is a port, not an incidental interface. It wraps a real BitTorrent swarm — network, DHT,
 * unbounded runtime — which no unit test can exercise, so it is the seam that makes the ingestion
 * use case testable in milliseconds.
 */
public interface TorrentDownloader {

    /**
     * Starts a download in the background.
     *
     * <p>{@code videoId} is passed rather than derived from {@code targetDirectory}: it is the
     * identity the rest of the system uses, and reading it back out of a path would couple this port
     * to a storage layout it does not own.
     *
     * @param videoId the ingestion this download belongs to
     * @param magnetUrl the {@code magnet:?xt=urn:btih:...} URI to fetch
     * @param targetDirectory directory to write torrent data into
     * @param onProgress called with the percentage downloaded (0-100) each time it advances by a
     *     whole point, and never with a lower number than it was last given. Called from a background
     *     thread, so it must be cheap and thread-safe. Publication stops once the returned future
     *     completes, but that is a guard rather than a fence: a tick already in flight can still
     *     arrive afterwards, so the consumer has to tolerate one.
     * @return the largest video file found once the download completes
     */
    CompletableFuture<Path> download(
            String videoId, String magnetUrl, Path targetDirectory, IntConsumer onProgress);
}
