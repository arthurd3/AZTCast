package com.azt.streaming.ingestion.application;

import com.azt.streaming.acquisition.domain.TorrentDownloader;
import com.azt.streaming.ingestion.domain.MagnetRegistry;
import com.azt.streaming.ingestion.domain.StreamJob;
import com.azt.streaming.ingestion.domain.StreamJobNotFoundException;
import com.azt.streaming.ingestion.domain.StreamJobRepository;
import com.azt.streaming.ingestion.domain.StreamJobStatus;
import com.azt.streaming.shared.storage.MediaStorage;
import com.azt.streaming.shared.storage.VideoCatalog;
import com.azt.streaming.transcoding.domain.MediaTranscoder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Drives the acquire -> transcode pipeline and records how far it got. */
@Service
@Slf4j
public class IngestionService {

    private final MediaStorage mediaStorage;
    private final VideoCatalog videoCatalog;
    private final TorrentDownloader torrentDownloader;
    private final MediaTranscoder mediaTranscoder;
    private final StreamJobRepository jobRepository;
    private final MagnetRegistry magnetRegistry;
    private final Clock clock;

    /**
     * Registered up front rather than looked up per call, so they appear in a scrape from the moment
     * the service starts. A counter that only exists after it has been incremented reads as "no data"
     * exactly when you most want to know the value is zero.
     */
    private final Counter deduplicated;

    private final Counter ready;
    private final Counter failed;

    public IngestionService(
            MediaStorage mediaStorage,
            VideoCatalog videoCatalog,
            TorrentDownloader torrentDownloader,
            MediaTranscoder mediaTranscoder,
            StreamJobRepository jobRepository,
            MagnetRegistry magnetRegistry,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.mediaStorage = mediaStorage;
        this.videoCatalog = videoCatalog;
        this.torrentDownloader = torrentDownloader;
        this.mediaTranscoder = mediaTranscoder;
        this.jobRepository = jobRepository;
        this.magnetRegistry = magnetRegistry;
        this.clock = clock;
        this.deduplicated = Counter.builder("aztcast.ingestion.deduplicated")
                .description("Ingestions short-circuited because the magnet was already known")
                .register(meterRegistry);
        this.ready = Counter.builder("aztcast.ingestion.completed")
                .description("Ingestions that reached a terminal state")
                .tag("outcome", "ready")
                .register(meterRegistry);
        this.failed = Counter.builder("aztcast.ingestion.completed")
                .description("Ingestions that reached a terminal state")
                .tag("outcome", "failed")
                .register(meterRegistry);
    }

    /**
     * Starts an ingestion and returns immediately with the job in DOWNLOADING.
     *
     * <p>Idempotent per magnet: posting the same link twice returns the first job rather than
     * downloading and transcoding the same torrent again. For an endpoint whose side effect is hours
     * of work, that is the behaviour a caller should be able to rely on — and a retry after a
     * timeout is exactly when it matters.
     */
    public StreamJob startIngestion(final String magnetUrl) {
        final String videoId = UUID.randomUUID().toString();

        Optional<StreamJob> alreadyRunning = existingIngestionOf(magnetUrl, videoId);
        if (alreadyRunning.isPresent()) {
            log.info("Ingestion for this magnet already exists as {}", alreadyRunning.get().videoId());
            // Counted because it is the only visible evidence deduplication is doing anything. A
            // silent optimisation that stops working looks exactly like one that is working.
            deduplicated.increment();
            return alreadyRunning.get();
        }

        final StreamJob job = jobRepository.save(StreamJob.downloading(videoId, magnetUrl, clock.instant()));
        final Path downloadDirectory = mediaStorage.downloadDirectoryFor(videoId);

        log.info("Ingestion {} started", videoId);

        // Closed before the status moves off DOWNLOADING, so a progress tick that was already in
        // flight cannot write the job back to DOWNLOADING once transcoding has started. The status
        // check inside recordProgress covers what this narrow flag cannot.
        final AtomicBoolean downloading = new AtomicBoolean(true);

        torrentDownloader
                .download(videoId, magnetUrl, downloadDirectory, percent -> {
                    if (downloading.get()) {
                        recordProgress(videoId, percent);
                    }
                })
                // thenCompose, not thenAccept. The previous version called the transcoder and threw
                // the returned future away, so the chain completed as soon as the *download* did and
                // every transcoding failure vanished — no log line, no status, nothing.
                .thenCompose(
                        videoFile -> {
                            downloading.set(false);
                            log.info("Ingestion {} downloaded to {}, transcoding", videoId, videoFile);
                            jobRepository.save(job.transcoding(clock.instant()));
                            // The downloaded filename is the only human-readable name this pipeline
                            // ever sees, and it is gone once the reaper takes the download directory.
                            // Recorded here, before the transcode, so the sidecar is already in place
                            // when master.m3u8 lands and the video becomes listable.
                            videoCatalog.record(videoId, videoFile.getFileName().toString());
                            return mediaTranscoder.transcodeToHls(
                                    videoFile, videoId, percent -> recordTranscodeProgress(videoId, percent));
                        })
                .whenComplete(
                        (ignored, error) -> {
                            if (error == null) {
                                log.info("Ingestion {} ready", videoId);
                                jobRepository.save(job.ready(clock.instant()));
                                ready.increment();
                            } else {
                                downloading.set(false);
                                log.error("Ingestion {} failed", videoId, error);
                                // Re-read so the failure keeps however far the download actually got,
                                // rather than resetting it to the 0% this closure captured at start.
                                StreamJob latest = jobRepository.findById(videoId).orElse(job);
                                jobRepository.save(latest.failed(rootCauseMessage(error), clock.instant()));
                                failed.increment();
                                // Release on failure, or a magnet that failed once could never be
                                // retried until its claim expired.
                                magnetRegistry.release(magnetUrl);
                            }
                        });

        return job;
    }

    /**
     * Writes a download percentage onto the job, if it is still downloading.
     *
     * <p>Re-read rather than derived from the job this ingestion started with: that record is a
     * snapshot from before the download began, and saving a mutation of it would undo any transition
     * that happened in between. The status check is what makes a late tick harmless — see the
     * {@code onProgress} contract on {@link TorrentDownloader}.
     */
    private void recordProgress(String videoId, int percent) {
        jobRepository
                .findById(videoId)
                .filter(current -> current.status() == StreamJobStatus.DOWNLOADING)
                .filter(current -> current.progressPercent() != percent)
                .ifPresent(current -> jobRepository.save(current.withProgress(percent, clock.instant())));
    }

    /**
     * Writes an encode percentage onto the job, if it is still transcoding.
     *
     * <p>Same shape as {@link #recordProgress} and for the same reasons, against a different field.
     * The status filter matters more here: this is called from ffmpeg's output-drain thread, which
     * outlives the process by however long the pipe takes to close, so a final tick can land after
     * the job has already been marked READY.
     */
    private void recordTranscodeProgress(String videoId, int percent) {
        jobRepository
                .findById(videoId)
                .filter(current -> current.status() == StreamJobStatus.TRANSCODING)
                .filter(current -> current.transcodePercent() == null || current.transcodePercent() != percent)
                .ifPresent(current -> jobRepository.save(current.withTranscodeProgress(percent, clock.instant())));
    }

    /**
     * @return the job that already owns {@code magnetUrl}, if any
     */
    private Optional<StreamJob> existingIngestionOf(String magnetUrl, String videoId) {
        Optional<String> owner = magnetRegistry.claim(magnetUrl, videoId);
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        Optional<StreamJob> job = jobRepository.findById(owner.get());
        if (job.isEmpty()) {
            // The claim outlived its job record — the media is gone or the job expired first. Drop
            // the claim and let this caller start over, rather than pointing them at nothing.
            log.info("Magnet claim {} has no job record; releasing it and re-ingesting", owner.get());
            magnetRegistry.release(magnetUrl);
            magnetRegistry.claim(magnetUrl, videoId);
        }
        return job;
    }

    public StreamJob findJob(String videoId) {
        return jobRepository.findById(videoId).orElseThrow(() -> new StreamJobNotFoundException(videoId));
    }

    /**
     * Ingestions still downloading or transcoding, so a client that lost its ids can find them again.
     *
     * <p>The repository has been able to answer this since durable job state arrived, but only
     * startup asked — which left a browser refresh as the one way to permanently lose track of a
     * download that was still running perfectly well.
     */
    public List<StreamJob> listActiveJobs() {
        return jobRepository.findUnfinished();
    }

    private static String rootCauseMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
