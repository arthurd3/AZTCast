package com.azt.streaming.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.azt.streaming.acquisition.domain.TorrentDownloadException;
import com.azt.streaming.acquisition.domain.TorrentDownloader;
import com.azt.streaming.ingestion.domain.MagnetRegistry;
import com.azt.streaming.ingestion.infrastructure.InMemoryStreamJobRepository;
import com.azt.streaming.ingestion.domain.StreamJob;
import com.azt.streaming.ingestion.domain.StreamJobNotFoundException;
import com.azt.streaming.ingestion.domain.StreamJobStatus;
import com.azt.streaming.shared.storage.MediaStorage;
import com.azt.streaming.shared.storage.VideoCatalog;
import com.azt.streaming.transcoding.domain.MediaTranscoder;
import com.azt.streaming.transcoding.domain.TranscodingException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IngestionServiceTest {

    private static final String MAGNET = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567";
    private static final Path VIDEO_FILE = Path.of("/downloads/x/movie.mkv");

    @Mock private MediaStorage mediaStorage;
    @Mock private VideoCatalog videoCatalog;
    @Mock private TorrentDownloader torrentDownloader;
    @Mock private MediaTranscoder mediaTranscoder;

    private InMemoryStreamJobRepository jobRepository;
    private IngestionService service;

    /**
     * Always wins the claim, i.e. the no-Redis behaviour. Deduplication has its own test; every
     * other case here is about the pipeline, and a stubbed registry would only add noise.
     */
    private static final MagnetRegistry ALWAYS_CLAIMS = new MagnetRegistry() {
        @Override
        public Optional<String> claim(String magnetUrl, String videoId) {
            return Optional.empty();
        }

        @Override
        public void release(String magnetUrl) {
            // nothing to release
        }
    };

    @BeforeEach
    void setUp() {
        jobRepository = new InMemoryStreamJobRepository();
        service =
                new IngestionService(
                        mediaStorage,
                        videoCatalog,
                        torrentDownloader,
                        mediaTranscoder,
                        jobRepository,
                        ALWAYS_CLAIMS,
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                        Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC));
        // lenient: the lookup-only test never starts an ingestion, so it never uses this.
        Mockito.lenient()
                .when(mediaStorage.downloadDirectoryFor(any()))
                .thenReturn(Path.of("/downloads/x"));
    }

    @Test
    void returnsImmediatelyWithTheJobDownloading() {
        given(torrentDownloader.download(any(), any())).willReturn(new CompletableFuture<>());

        StreamJob job = service.startIngestion(MAGNET);

        assertThat(job.status()).isEqualTo(StreamJobStatus.DOWNLOADING);
        assertThat(job.videoId()).isNotBlank();
        assertThat(job.streamUrl()).as("no playlist exists yet").isNull();
    }

    @Test
    void reachesReadyOnlyAfterTranscodingCompletes() {
        CompletableFuture<Void> transcode = new CompletableFuture<>();
        given(torrentDownloader.download(any(), any()))
                .willReturn(CompletableFuture.completedFuture(VIDEO_FILE));
        given(mediaTranscoder.transcodeToHls(any(), any())).willReturn(transcode);

        String videoId = service.startIngestion(MAGNET).videoId();

        // The download has already finished. If the transcode future were discarded — as it was
        // before this change — the chain would have completed and the job would read READY here.
        assertThat(status(videoId)).isEqualTo(StreamJobStatus.TRANSCODING);

        transcode.complete(null);
        assertThat(status(videoId)).isEqualTo(StreamJobStatus.READY);
    }

    @Test
    void recordsTheDownloadedFilenameAsTheTitle() {
        // The only human-readable name this pipeline ever sees, and it is gone once the reaper takes
        // the download directory — so it has to be captured while the path is still in hand.
        given(torrentDownloader.download(any(), any()))
                .willReturn(CompletableFuture.completedFuture(VIDEO_FILE));
        given(mediaTranscoder.transcodeToHls(any(), any())).willReturn(new CompletableFuture<>());

        String videoId = service.startIngestion(MAGNET).videoId();

        Mockito.verify(videoCatalog).record(videoId, "movie.mkv");
    }

    @Test
    void recordsTranscodingFailuresThatUsedToVanish() {
        given(torrentDownloader.download(any(), any()))
                .willReturn(CompletableFuture.completedFuture(VIDEO_FILE));
        given(mediaTranscoder.transcodeToHls(any(), any()))
                .willReturn(
                        CompletableFuture.failedFuture(new TranscodingException("ffmpeg exited with code 1")));

        StreamJob job = service.findJob(service.startIngestion(MAGNET).videoId());

        assertThat(job.status()).isEqualTo(StreamJobStatus.FAILED);
        assertThat(job.failureReason()).isEqualTo("ffmpeg exited with code 1");
    }

    @Test
    void recordsAcquisitionFailures() {
        given(torrentDownloader.download(any(), any()))
                .willReturn(
                        CompletableFuture.failedFuture(new TorrentDownloadException("No video file found")));

        StreamJob job = service.findJob(service.startIngestion(MAGNET).videoId());

        assertThat(job.status()).isEqualTo(StreamJobStatus.FAILED);
        assertThat(job.failureReason()).isEqualTo("No video file found");
    }

    @Test
    void exposesTheStreamUrlOnlyWhenReady() {
        given(torrentDownloader.download(any(), any()))
                .willReturn(CompletableFuture.completedFuture(VIDEO_FILE));
        given(mediaTranscoder.transcodeToHls(any(), any()))
                .willReturn(CompletableFuture.completedFuture(null));

        String videoId = service.startIngestion(MAGNET).videoId();

        assertThat(service.findJob(videoId).streamUrl())
                .isEqualTo("/api/v1/stream/" + videoId + "/master.m3u8");
    }

    @Test
    void throwsForAnUnknownJob() {
        assertThatThrownBy(() -> service.findJob("nope")).isInstanceOf(StreamJobNotFoundException.class);
    }

    private StreamJobStatus status(String videoId) {
        return jobRepository.findById(videoId).map(StreamJob::status).orElseThrow();
    }
}
