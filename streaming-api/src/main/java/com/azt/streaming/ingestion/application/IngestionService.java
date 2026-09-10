package com.azt.streaming.ingestion.application;

import com.azt.streaming.acquisition.domain.TorrentDownloader;
import com.azt.streaming.ingestion.domain.MagnetRegistry;
import com.azt.streaming.ingestion.domain.RepairAction;
import com.azt.streaming.ingestion.domain.StreamJob;
import com.azt.streaming.ingestion.domain.StreamJobNotFoundException;
import com.azt.streaming.ingestion.domain.StreamJobRepository;
import com.azt.streaming.ingestion.domain.StreamJobStatus;
import com.azt.streaming.ingestion.domain.VideoNotRepairableException;
import com.azt.streaming.shared.config.StreamingProperties;
import com.azt.streaming.shared.storage.InsufficientStorageException;
import com.azt.streaming.shared.storage.MediaStorage;
import com.azt.streaming.shared.storage.VideoCatalog;
import com.azt.streaming.shared.storage.VideoIsKeptException;
import com.azt.streaming.shared.storage.VideoNotFoundException;
import com.azt.streaming.transcoding.domain.AudioPlan;
import com.azt.streaming.transcoding.domain.LadderReport;
import com.azt.streaming.transcoding.domain.MediaTranscoder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
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
    private final StreamingProperties properties;
    private final Clock clock;

    /**
     * Registered up front rather than looked up per call, so they appear in a scrape from the moment
     * the service starts. A counter that only exists after it has been incremented reads as "no data"
     * exactly when you most want to know the value is zero.
     */
    private final Counter deduplicated;

    private final Counter ready;
    private final Counter failed;
    private final Counter repaired;

    public IngestionService(
            MediaStorage mediaStorage,
            VideoCatalog videoCatalog,
            TorrentDownloader torrentDownloader,
            MediaTranscoder mediaTranscoder,
            StreamJobRepository jobRepository,
            MagnetRegistry magnetRegistry,
            StreamingProperties properties,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.mediaStorage = mediaStorage;
        this.videoCatalog = videoCatalog;
        this.torrentDownloader = torrentDownloader;
        this.mediaTranscoder = mediaTranscoder;
        this.jobRepository = jobRepository;
        this.magnetRegistry = magnetRegistry;
        this.properties = properties;
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
        this.repaired = Counter.builder("aztcast.ingestion.completed")
                .description("Ingestions that reached a terminal state")
                .tag("outcome", "repaired")
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
        // Before the claim, not after: a refused ingestion must not leave a magnet claimed by a
        // videoId that never existed, or the next attempt would be deduplicated into nothing.
        requireRoomToStart();

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
                            videoCatalog.record(videoId, videoFile.getFileName().toString(), magnetUrl);
                            return mediaTranscoder.transcodeToHls(
                                    videoFile, videoId, percent -> recordTranscodeProgress(videoId, percent));
                        })
                .whenComplete(
                        (ignored, error) -> {
                            if (error == null) {
                                log.info("Ingestion {} ready", videoId);
                                jobRepository.save(job.ready(clock.instant()));
                                ready.increment();
                                // The ladder is verified by the time this runs, so the torrent it
                                // was built from is a second full copy of a video nobody watches and
                                // nothing seeds. Freeing it here rather than leaving it for the
                                // reaper is the difference between minutes and a day of holding it.
                                mediaStorage.discardDownload(videoId);
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
     * Puts a video that stopped working back together, without changing its id.
     *
     * <p>The id is the whole point. A viewer's link, the library card, a bookmark and the keep
     * marker are all {@code videoId}; re-ingesting the magnet would produce a second video under a
     * second id and leave the first one broken and listed. {@link #startIngestion} always mints a
     * fresh UUID — deliberately, it is starting something new — so this is a separate path rather
     * than an argument to it.
     *
     * <p>It does the cheapest thing that works:
     *
     * <ol>
     *   <li>Nothing, if the ladder is sound and playable.
     *   <li>Rebuild the manifests, if the media is intact and only the playlists are wrong. This is
     *       the common case after a change to what the master advertises, and it costs seconds
     *       rather than an encode.
     *   <li>Transcode again from the retained download, if segments are missing.
     *   <li>Fetch the torrent again from the recorded magnet, if the download is gone too.
     * </ol>
     *
     * @throws com.azt.streaming.shared.storage.VideoNotFoundException if nothing is on disk for it
     * @throws com.azt.streaming.ingestion.domain.VideoNotRepairableException if it is broken and
     *     there is nothing left to rebuild it from
     */
    public RepairAction repair(String videoId) {
        LadderReport report = mediaTranscoder.inspect(videoId);
        Optional<Path> download = mediaStorage.existingDownload(videoId);

        if (mediaStorage.resolveHlsAsset(videoId, MediaStorage.POSTER).isEmpty()
                && download.isEmpty()
                && report.problems().contains("master.m3u8 is missing or unreadable")) {
            // No poster, no download, no master: there is no video here to repair, as opposed to a
            // video that is broken.
            throw new VideoNotFoundException(videoId);
        }

        if (report.isSound()) {
            return repairSoundLadder(videoId, report, download);
        }
        log.warn("Repairing {}: {}", videoId, report.summary());

        if (download.isPresent()) {
            if (report.mediaIntact()) {
                // The segments are bit-for-bit correct and the playlists describing them are not.
                // Re-encoding would spend minutes to produce identical output.
                startRepairJob(videoId, null, () -> mediaTranscoder.republish(download.get(), videoId));
                return RepairAction.MANIFESTS_REBUILT;
            }
            mediaStorage.discardIncompleteHls(videoId);
            startRepairJob(
                    videoId,
                    null,
                    () -> mediaTranscoder.transcodeToHls(
                            download.get(), videoId, percent -> recordTranscodeProgress(videoId, percent)));
            return RepairAction.RETRANSCODED;
        }

        String magnetUrl = videoCatalog
                .sourceMagnetOf(videoId)
                .orElseThrow(() -> new VideoNotRepairableException(
                        videoId,
                        "its media is incomplete, the download has been reaped, and no magnet was recorded for it"));

        mediaStorage.discardIncompleteHls(videoId);
        Path downloadDirectory = mediaStorage.downloadDirectoryFor(videoId);
        startRepairJob(
                videoId,
                magnetUrl,
                () -> torrentDownloader
                        .download(videoId, magnetUrl, downloadDirectory, percent -> recordProgress(videoId, percent))
                        .thenCompose(videoFile -> {
                            jobRepository
                                    .findById(videoId)
                                    .ifPresent(job -> jobRepository.save(job.transcoding(clock.instant())));
                            return mediaTranscoder.transcodeToHls(
                                    videoFile, videoId, percent -> recordTranscodeProgress(videoId, percent));
                        }));
        return RepairAction.REFETCHED;
    }

    /**
     * A ladder with nothing wrong with it may still not be the best this host can do.
     *
     * <p>The case that matters: the video was published on a build with no decoder for its audio,
     * so the track was copied through untouched and everything but Apple's platforms plays it
     * silently. Installing a decoder changes what this host would produce and nothing else in the
     * system would ever notice — the ladder is complete, playable and listed either way.
     *
     * <p>Costs one ffprobe of the source, and only when there is a source to probe.
     */
    private RepairAction repairSoundLadder(String videoId, LadderReport report, Optional<Path> download) {
        if (download.isEmpty()) {
            log.info("Repair for {} found nothing to do", videoId);
            return RepairAction.NOTHING_TO_DO;
        }
        AudioPlan planned = mediaTranscoder.plannedAudio(download.get());
        if (!planned.present() || !report.audioWouldImproveTo(planned.codecs(), planned.channels())) {
            log.info("Repair for {} found nothing to do", videoId);
            return RepairAction.NOTHING_TO_DO;
        }

        log.info(
                "Repairing the audio of {}: published as {}, this host can now produce {} at {} channel(s)",
                videoId,
                report.audio().map(LadderReport.PublishedAudio::codecs).orElse("nothing"),
                planned.codecs(),
                planned.channels());
        startRepairJob(videoId, null, () -> mediaTranscoder.rebuildAudio(download.get(), videoId));
        return RepairAction.AUDIO_REBUILT;
    }

    /**
     * Runs a repair in the background under a job the player can poll, exactly like an ingestion.
     *
     * <p>The job record is replaced rather than amended: whatever it said before, this video is
     * being worked on again now, and a viewer watching the same endpoint should see that.
     *
     * @param magnetUrl the source being fetched, or null when the repair needs no network
     */
    private void startRepairJob(String videoId, String magnetUrl, Supplier<CompletableFuture<Void>> work) {
        StreamJob job = jobRepository.save(
                magnetUrl == null
                        ? StreamJob.downloading(videoId, null, clock.instant()).transcoding(clock.instant())
                        : StreamJob.downloading(videoId, magnetUrl, clock.instant()));
        work.get()
                .whenComplete((ignored, error) -> {
                    if (error == null) {
                        log.info("Repair of {} finished", videoId);
                        jobRepository.save(job.ready(clock.instant()));
                        repaired.increment();
                    } else {
                        log.error("Repair of {} failed", videoId, error);
                        StreamJob latest = jobRepository.findById(videoId).orElse(job);
                        jobRepository.save(latest.failed(rootCauseMessage(error), clock.instant()));
                        failed.increment();
                    }
                });
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

    /**
     * Deletes a video and everything the pipeline knows about it.
     *
     * <p>The only way media leaves this disk. Nothing schedules it, nothing infers it from watch
     * history, and there is no window after which it happens on its own — see ADR-0030. That makes
     * this the one destructive operation in the API, which is why the {@code kept} marker stops it.
     *
     * <p>Four things go, in an order chosen so a failure part-way through cannot strand the caller.
     * The sidecar is read first, because it lives inside the directory that is about to go. The
     * media goes next, and if none of it was there the caller gets a 404 rather than a cheerful 204
     * for a video that never existed. Only then are the job record and the magnet claim released —
     * the claim especially, because a claim that outlives its media makes re-adding the same magnet
     * hand back the deleted id, and the caller a library card that 404s when clicked.
     *
     * @param force delete even if the video is marked as kept
     * @throws VideoNotFoundException if there is nothing on disk under this id
     * @throws VideoIsKeptException if it is kept and {@code force} is false
     */
    public void delete(String videoId, boolean force) {
        if (!force && videoCatalog.isKept(videoId)) {
            throw new VideoIsKeptException(videoId);
        }

        Optional<String> magnetUrl = videoCatalog.sourceMagnetOf(videoId);

        boolean removedHls = mediaStorage.discardHls(videoId);
        boolean removedDownload = mediaStorage.discardDownload(videoId);
        if (!removedHls && !removedDownload) {
            throw new VideoNotFoundException(videoId);
        }

        jobRepository.delete(videoId);
        magnetUrl.ifPresent(magnetRegistry::release);
        log.info("Deleted video {}", videoId);
    }

    /**
     * Refuses an ingestion that would start with the media volume below its floor.
     *
     * <p>An unreadable filesystem is not a refusal. The floor exists to stop a download that cannot
     * finish; declining to start one because a stat call failed would be a different, worse policy
     * wearing the same clothes.
     */
    private void requireRoomToStart() {
        long floor = properties.storage().minFreeSpace().toBytes();
        if (floor <= 0) {
            return;
        }
        long usable = mediaStorage.volumeSpace().map(space -> space.usableBytes()).orElse(Long.MAX_VALUE);
        if (usable < floor) {
            throw new InsufficientStorageException(usable, floor);
        }
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
