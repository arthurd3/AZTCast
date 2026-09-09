package com.azt.streaming.acquisition.infrastructure;

import bt.Bt;
import bt.data.Storage;
import bt.data.file.FileSystemStorage;
import bt.magnet.MagnetUriParser;
import bt.metainfo.TorrentId;
import bt.runtime.BtClient;
import bt.runtime.BtRuntime;
import bt.torrent.TorrentSessionState;
import bt.torrent.selector.SequentialSelector;
import com.azt.streaming.acquisition.domain.TorrentDownloadException;
import com.azt.streaming.acquisition.domain.TorrentDownloader;
import com.azt.streaming.shared.config.StreamingProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
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

    /**
     * How often each live connection's byte counters are written down.
     *
     * <p>Slower than the poll on purpose. The counters are what makes the provider log say who
     * actually served a video rather than merely who was present, but a swarm of fifty peers
     * sampled every second would be fifty database writes a second to watch numbers creep. Ten
     * seconds is fine resolution for a download measured in minutes.
     */
    private static final Duration TRANSFER_SAMPLE_INTERVAL = Duration.ofSeconds(10);

    private final BtRuntime runtime;
    private final PeerEventRecorder peerEvents;
    private final VideoFileLocator videoFileLocator;
    private final Duration downloadTimeout;
    private final Duration progressLogInterval;

    public BtTorrentDownloader(
            BtRuntime btRuntime,
            PeerEventRecorder peerEvents,
            VideoFileLocator videoFileLocator,
            StreamingProperties properties) {
        this.runtime = btRuntime;
        this.peerEvents = peerEvents;
        this.videoFileLocator = videoFileLocator;
        this.downloadTimeout = properties.torrent().downloadTimeout();
        this.progressLogInterval = properties.torrent().progressLogInterval();
    }

    @Override
    public CompletableFuture<Path> download(
            final String videoId,
            final String magnetUrl,
            final Path targetDirectory,
            final IntConsumer onProgress) {
        final CompletableFuture<Path> result = new CompletableFuture<>();
        final Storage storage = new FileSystemStorage(targetDirectory);

        // Read from the magnet rather than waited for: peers are discovered long before metadata
        // arrives, and attributing them needs the torrent's identity from the first event onward.
        // Lenient, because the parser this uses is stricter about tracker lists than the swarm is,
        // and a magnet that downloads fine should not lose its peer log to a pedantic parse.
        final TorrentId torrentId = MagnetUriParser.lenientParser().parse(magnetUrl).getTorrentId();
        peerEvents.track(torrentId, videoId);

        // Attached to the shared runtime rather than standing up its own. Config, DHT and the
        // extension switches all belong to the runtime now; what is left here is per-torrent.
        final BtClient client =
                Bt.client(runtime)
                        .storage(storage)
                        .magnet(magnetUrl)
                        // Sequential pieces so playback can start before the whole file lands.
                        .selector(SequentialSelector.sequential())
                        .stopWhenDownloaded()
                        .build();

        // The client used to be built, started and then forgotten: a stalled or failed torrent
        // leaked its threads and sockets for the lifetime of the JVM. Releasing it from
        // whenComplete covers every exit — success, failure and timeout alike.
        result.whenComplete(
                (path, error) -> {
                    // Stop first, untrack second. Closing the client is what produces the
                    // disconnect events, and those carry each connection's final duration — untrack
                    // ahead of them and every one is dropped as belonging to nothing.
                    stopQuietly(client, magnetUrl);
                    peerEvents.untrack(torrentId);
                });

        // Without this a magnet that never finds peers hangs forever.
        result.orTimeout(downloadTimeout.toMillis(), TimeUnit.MILLISECONDS);

        final Progress progress = new Progress(
                onProgress,
                new AtomicInteger(-1),
                new AtomicLong(System.nanoTime()),
                new AtomicLong(System.nanoTime()));

        client.startAsync(
                state -> onSessionState(state, result, torrentId, magnetUrl, targetDirectory, progress),
                POLL_INTERVAL.toMillis());

        log.info("Started background download {} for magnet {} into {}", videoId, magnetUrl, targetDirectory);
        return result;
    }

    private void onSessionState(
            TorrentSessionState state,
            CompletableFuture<Path> result,
            TorrentId torrentId,
            String magnetUrl,
            Path targetDirectory,
            Progress progress) {

        if (state.getPiecesRemaining() != 0) {
            reportProgress(state, result, progress);
            sampleTransfersOccasionally(state, torrentId, progress.lastSampleNanos());
            logProgressOccasionally(state, magnetUrl, progress.lastLogNanos());
            return;
        }

        // One last look before the client stops and every connection with it. This is the only
        // point at which the final byte counts exist and the torrent is still being attributed.
        peerEvents.sampleTransfers(torrentId, state.getConnectedPeers());

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

    /**
     * Publishes the download percentage, at most once per whole point.
     *
     * <p>Polling is once a second for the whole of a download that can run for hours, and every
     * published tick costs a job write (a Redis round trip when it is enabled). Whole points cap that
     * at a hundred writes per torrent, which is the difference between a progress bar and a hot loop.
     *
     * <p>Monotonic on purpose: only forward movement is published, so a stale poll cannot walk the
     * bar backwards. The {@code isDone} check stops the stream once the download has finished — it is
     * a guard, not a fence, so the consumer still has to tolerate a late tick.
     */
    private static void reportProgress(
            TorrentSessionState state, CompletableFuture<Path> result, Progress progress) {
        if (result.isDone()) {
            return;
        }
        int percent = (int) progressPercent(state);
        int previous = progress.lastPercent().get();
        if (percent <= previous || !progress.lastPercent().compareAndSet(previous, percent)) {
            return;
        }
        progress.sink().accept(percent);
    }

    private void sampleTransfersOccasionally(
            TorrentSessionState state, TorrentId torrentId, AtomicLong lastSampleNanos) {
        if (!due(lastSampleNanos, TRANSFER_SAMPLE_INTERVAL)) {
            return;
        }
        peerEvents.sampleTransfers(torrentId, state.getConnectedPeers());
    }

    /** Whether {@code interval} has passed since the last time this returned true. */
    private static boolean due(AtomicLong lastNanos, Duration interval) {
        long now = System.nanoTime();
        long previous = lastNanos.get();
        return now - previous >= interval.toNanos() && lastNanos.compareAndSet(previous, now);
    }

    /** Per-download progress bookkeeping: where to publish, and what has been published already. */
    private record Progress(
            IntConsumer sink, AtomicInteger lastPercent, AtomicLong lastLogNanos, AtomicLong lastSampleNanos) {}

    private void logProgressOccasionally(
            TorrentSessionState state, String magnetUrl, AtomicLong lastProgressLogNanos) {
        if (!due(lastProgressLogNanos, progressLogInterval)) {
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
