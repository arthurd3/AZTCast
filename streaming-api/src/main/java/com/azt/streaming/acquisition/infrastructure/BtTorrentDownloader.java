package com.azt.streaming.acquisition.infrastructure;

import bt.Bt;
import bt.data.Storage;
import bt.data.file.FileSystemStorage;
import bt.runtime.BtClient;
import bt.runtime.Config;
import bt.torrent.TorrentSessionState;
import bt.torrent.selector.SequentialSelector;
import com.azt.streaming.acquisition.domain.TorrentDownloadException;
import com.azt.streaming.acquisition.domain.TorrentDownloader;
import com.azt.streaming.shared.config.StreamingProperties;
import com.google.inject.Module;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** {@link TorrentDownloader} backed by the {@code bt} library. */
@Service
@Slf4j
public class BtTorrentDownloader implements TorrentDownloader {

    /**
     * How often the session state is sampled. Kept short so completion is detected promptly;
     * progress <em>logging</em> is throttled separately, which is what used to spam the log at INFO
     * once per second per torrent.
     */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

    private final Config config;
    private final Module dhtModule;
    private final VideoFileLocator videoFileLocator;
    private final Duration downloadTimeout;
    private final Duration progressLogInterval;

    public BtTorrentDownloader(
            Config btConfig,
            Module btDhtModule,
            VideoFileLocator videoFileLocator,
            StreamingProperties properties) {
        this.config = btConfig;
        this.dhtModule = btDhtModule;
        this.videoFileLocator = videoFileLocator;
        this.downloadTimeout = properties.torrent().downloadTimeout();
        this.progressLogInterval = properties.torrent().progressLogInterval();
    }

    @Override
    public CompletableFuture<Path> download(final String magnetUrl, final Path targetDirectory) {
        final CompletableFuture<Path> result = new CompletableFuture<>();
        final Storage storage = new FileSystemStorage(targetDirectory);

        final BtClient client =
                Bt.client()
                        .config(config)
                        .storage(storage)
                        .magnet(magnetUrl)
                        .autoLoadModules()
                        .module(dhtModule)
                        // Sequential pieces so playback can start before the whole file lands.
                        .selector(SequentialSelector.sequential())
                        .stopWhenDownloaded()
                        .build();

        // The client used to be built, started and then forgotten: a stalled or failed torrent
        // leaked its threads and sockets for the lifetime of the JVM. Releasing it from
        // whenComplete covers every exit — success, failure and timeout alike.
        result.whenComplete((path, error) -> stopQuietly(client, magnetUrl));

        // Without this a magnet that never finds peers hangs forever.
        result.orTimeout(downloadTimeout.toMillis(), TimeUnit.MILLISECONDS);

        final AtomicLong lastProgressLogNanos = new AtomicLong(System.nanoTime());

        client.startAsync(
                state -> onSessionState(state, result, magnetUrl, targetDirectory, lastProgressLogNanos),
                POLL_INTERVAL.toMillis());

        log.info("Started background download for magnet {} into {}", magnetUrl, targetDirectory);
        return result;
    }

    private void onSessionState(
            TorrentSessionState state,
            CompletableFuture<Path> result,
            String magnetUrl,
            Path targetDirectory,
            AtomicLong lastProgressLogNanos) {

        if (state.getPiecesRemaining() != 0) {
            logProgressOccasionally(state, magnetUrl, lastProgressLogNanos);
            return;
        }

        log.info("Download finished for magnet {}", magnetUrl);
        try {
            videoFileLocator
                    .locateLargestVideo(targetDirectory)
                    .ifPresentOrElse(
                            result::complete,
                            () ->
                                    result.completeExceptionally(
                                            new TorrentDownloadException(
                                                    "No video file found under " + targetDirectory)));
        } catch (RuntimeException e) {
            result.completeExceptionally(
                    new TorrentDownloadException("Failed to locate a video file in the torrent", e));
        }
    }

    private void logProgressOccasionally(
            TorrentSessionState state, String magnetUrl, AtomicLong lastProgressLogNanos) {
        long now = System.nanoTime();
        long previous = lastProgressLogNanos.get();
        if (now - previous < progressLogInterval.toNanos()
                || !lastProgressLogNanos.compareAndSet(previous, now)) {
            return;
        }
        log.info("Progress {}% for magnet {}", String.format("%.1f", progressPercent(state)), magnetUrl);
    }

    /**
     * Percentage of pieces downloaded.
     *
     * <p>The zero check is load-bearing: before torrent metadata arrives {@code getPiecesTotal()} is
     * 0, and the previous version divided by it on every poll.
     */
    private static double progressPercent(TorrentSessionState state) {
        int total = state.getPiecesTotal();
        if (total <= 0) {
            return 0.0;
        }
        return (double) (total - state.getPiecesRemaining()) / total * 100.0;
    }

    private static void stopQuietly(BtClient client, String magnetUrl) {
        try {
            client.stop();
        } catch (RuntimeException e) {
            log.warn("Failed to stop the BitTorrent client for magnet {}", magnetUrl, e);
        }
    }
}
