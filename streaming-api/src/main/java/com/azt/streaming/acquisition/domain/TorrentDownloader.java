package com.azt.streaming.acquisition.domain;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

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
     * @param magnetUrl the {@code magnet:?xt=urn:btih:...} URI to fetch
     * @param targetDirectory directory to write torrent data into
     * @return the largest video file found once the download completes
     */
    CompletableFuture<Path> download(String magnetUrl, Path targetDirectory);
}
