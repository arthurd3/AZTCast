package com.azt.streaming.ingestion.application;

import com.azt.streaming.acquisition.domain.TorrentDownloader;
import com.azt.streaming.ingestion.domain.MagnetRegistry;
import com.azt.streaming.ingestion.domain.StreamJob;
import com.azt.streaming.ingestion.domain.StreamJobNotFoundException;
import com.azt.streaming.ingestion.domain.StreamJobRepository;
import com.azt.streaming.shared.storage.MediaStorage;
import com.azt.streaming.transcoding.domain.MediaTranscoder;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Drives the acquire -> transcode pipeline and records how far it got. */
@Service
@Slf4j
@RequiredArgsConstructor
public class IngestionService {

    private final MediaStorage mediaStorage;
    private final TorrentDownloader torrentDownloader;
    private final MediaTranscoder mediaTranscoder;
    private final StreamJobRepository jobRepository;
    private final MagnetRegistry magnetRegistry;
    private final Clock clock;

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
            return alreadyRunning.get();
        }

        final StreamJob job = jobRepository.save(StreamJob.downloading(videoId, magnetUrl, clock.instant()));
        final Path downloadDirectory = mediaStorage.downloadDirectoryFor(videoId);

        log.info("Ingestion {} started", videoId);

        torrentDownloader
                .download(magnetUrl, downloadDirectory)
                // thenCompose, not thenAccept. The previous version called the transcoder and threw
                // the returned future away, so the chain completed as soon as the *download* did and
                // every transcoding failure vanished — no log line, no status, nothing.
                .thenCompose(
                        videoFile -> {
                            log.info("Ingestion {} downloaded to {}, transcoding", videoId, videoFile);
                            jobRepository.save(job.transcoding(clock.instant()));
                            return mediaTranscoder.transcodeToHls(videoFile, videoId);
                        })
                .whenComplete(
                        (ignored, error) -> {
                            if (error == null) {
                                log.info("Ingestion {} ready", videoId);
                                jobRepository.save(job.ready(clock.instant()));
                            } else {
                                log.error("Ingestion {} failed", videoId, error);
                                jobRepository.save(job.failed(rootCauseMessage(error), clock.instant()));
                                // Release on failure, or a magnet that failed once could never be
                                // retried until its claim expired.
                                magnetRegistry.release(magnetUrl);
                            }
                        });

        return job;
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

    private static String rootCauseMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
