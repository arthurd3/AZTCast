package com.azt.streaming.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.azt.streaming.acquisition.domain.TorrentDownloadException;
import com.azt.streaming.acquisition.domain.TorrentDownloader;
import com.azt.streaming.ingestion.domain.MagnetRegistry;
import com.azt.streaming.ingestion.domain.RepairAction;
import com.azt.streaming.ingestion.infrastructure.InMemoryStreamJobRepository;
import com.azt.streaming.ingestion.domain.StreamJob;
import com.azt.streaming.ingestion.domain.StreamJobNotFoundException;
import com.azt.streaming.ingestion.domain.StreamJobStatus;
import com.azt.streaming.ingestion.domain.VideoNotRepairableException;
import com.azt.streaming.shared.storage.MediaStorage;
import com.azt.streaming.shared.storage.VideoCatalog;
import com.azt.streaming.transcoding.domain.LadderReport;
import com.azt.streaming.transcoding.domain.MediaTranscoder;
import com.azt.streaming.transcoding.domain.TranscodingException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
        given(torrentDownloader.download(any(), any(), any(), any())).willReturn(new CompletableFuture<>());

        StreamJob job = service.startIngestion(MAGNET);

        assertThat(job.status()).isEqualTo(StreamJobStatus.DOWNLOADING);
        assertThat(job.videoId()).isNotBlank();
        assertThat(job.streamUrl()).as("no playlist exists yet").isNull();
    }

    @Test
    void reachesReadyOnlyAfterTranscodingCompletes() {
        CompletableFuture<Void> transcode = new CompletableFuture<>();
        given(torrentDownloader.download(any(), any(), any(), any()))
                .willReturn(CompletableFuture.completedFuture(VIDEO_FILE));
        given(mediaTranscoder.transcodeToHls(any(), any(), any())).willReturn(transcode);

        String videoId = service.startIngestion(MAGNET).videoId();

        // The download has already finished. If the transcode future were discarded — as it was
        // before this change — the chain would have completed and the job would read READY here.
        assertThat(status(videoId)).isEqualTo(StreamJobStatus.TRANSCODING);

        transcode.complete(null);
        assertThat(status(videoId)).isEqualTo(StreamJobStatus.READY);
    }

    @Test
    void recordsTheDownloadedFilenameAndTheMagnetItCameFrom() {
        // The filename is the only human-readable name this pipeline ever sees, and it is gone once
        // the reaper takes the download directory. The magnet is the only way back to the source at
        // all — job state is in memory by default and the claim is keyed by infohash — so without
        // it a video that later loses a segment cannot be repaired.
        given(torrentDownloader.download(any(), any(), any(), any()))
                .willReturn(CompletableFuture.completedFuture(VIDEO_FILE));
        given(mediaTranscoder.transcodeToHls(any(), any(), any())).willReturn(new CompletableFuture<>());

        String videoId = service.startIngestion(MAGNET).videoId();

        Mockito.verify(videoCatalog).record(videoId, "movie.mkv", MAGNET);
    }

    @Test
    void recordsTranscodingFailuresThatUsedToVanish() {
        given(torrentDownloader.download(any(), any(), any(), any()))
                .willReturn(CompletableFuture.completedFuture(VIDEO_FILE));
        given(mediaTranscoder.transcodeToHls(any(), any(), any()))
                .willReturn(
                        CompletableFuture.failedFuture(new TranscodingException("ffmpeg exited with code 1")));

        StreamJob job = service.findJob(service.startIngestion(MAGNET).videoId());

        assertThat(job.status()).isEqualTo(StreamJobStatus.FAILED);
        assertThat(job.failureReason()).isEqualTo("ffmpeg exited with code 1");
    }

    @Test
    void recordsAcquisitionFailures() {
        given(torrentDownloader.download(any(), any(), any(), any()))
                .willReturn(
                        CompletableFuture.failedFuture(new TorrentDownloadException("No video file found")));

        StreamJob job = service.findJob(service.startIngestion(MAGNET).videoId());

        assertThat(job.status()).isEqualTo(StreamJobStatus.FAILED);
        assertThat(job.failureReason()).isEqualTo("No video file found");
    }

    @Test
    void exposesTheStreamUrlOnlyWhenReady() {
        given(torrentDownloader.download(any(), any(), any(), any()))
                .willReturn(CompletableFuture.completedFuture(VIDEO_FILE));
        given(mediaTranscoder.transcodeToHls(any(), any(), any()))
                .willReturn(CompletableFuture.completedFuture(null));

        String videoId = service.startIngestion(MAGNET).videoId();

        assertThat(service.findJob(videoId).streamUrl())
                .isEqualTo("/api/v1/stream/" + videoId + "/master.m3u8");
    }

    @Test
    void throwsForAnUnknownJob() {
        assertThatThrownBy(() -> service.findJob("nope")).isInstanceOf(StreamJobNotFoundException.class);
    }

    @Test
    void publishesDownloadProgressOntoTheJob() {
        // The percentage the swarm reports is the only measured progress in the pipeline, and it used
        // to reach a log line and nothing else — so a caller polling the job could not tell a torrent
        // moving at 90% from one stuck at 2%.
        given(torrentDownloader.download(any(), any(), any(), any())).willReturn(new CompletableFuture<>());

        String videoId = service.startIngestion(MAGNET).videoId();
        progressSink().accept(42);

        assertThat(service.findJob(videoId).progressPercent()).isEqualTo(42);
    }

    @Test
    void ignoresProgressOnceTheDownloadIsOver() {
        // A tick already in flight can land after the download completes. Without the status guard it
        // would write DOWNLOADING back over TRANSCODING, and the job would claim to be downloading
        // for the whole of a transcode that is already running.
        given(torrentDownloader.download(any(), any(), any(), any()))
                .willReturn(CompletableFuture.completedFuture(VIDEO_FILE));
        given(mediaTranscoder.transcodeToHls(any(), any(), any())).willReturn(new CompletableFuture<>());

        String videoId = service.startIngestion(MAGNET).videoId();
        progressSink().accept(99);

        StreamJob job = service.findJob(videoId);
        assertThat(job.status()).isEqualTo(StreamJobStatus.TRANSCODING);
        assertThat(job.progressPercent()).as("the download did finish").isEqualTo(100);
    }

    @Test
    void keepsHowFarADownloadGotWhenItFails() {
        CompletableFuture<Path> download = new CompletableFuture<>();
        given(torrentDownloader.download(any(), any(), any(), any())).willReturn(download);

        String videoId = service.startIngestion(MAGNET).videoId();
        progressSink().accept(37);
        download.completeExceptionally(new TorrentDownloadException("No seeders"));

        StreamJob job = service.findJob(videoId);
        assertThat(job.status()).isEqualTo(StreamJobStatus.FAILED);
        assertThat(job.progressPercent()).as("not reset to the 0% the job started at").isEqualTo(37);
    }

    @Test
    void listsOnlyIngestionsStillRunning() {
        // What a refreshed browser asks for: the downloads it can still pick back up.
        given(torrentDownloader.download(any(), any(), any(), any()))
                .willReturn(new CompletableFuture<>())
                .willReturn(CompletableFuture.completedFuture(VIDEO_FILE));
        given(mediaTranscoder.transcodeToHls(any(), any(), any()))
                .willReturn(CompletableFuture.completedFuture(null));

        String running = service.startIngestion(MAGNET).videoId();
        String finished = service.startIngestion(MAGNET + "2").videoId();

        assertThat(service.listActiveJobs()).extracting(StreamJob::videoId).containsExactly(running);
        assertThat(service.findJob(finished).status()).isEqualTo(StreamJobStatus.READY);
    }

    /** The progress callback the service handed to the downloader. */
    private IntConsumer progressSink() {
        ArgumentCaptor<IntConsumer> captor = ArgumentCaptor.forClass(IntConsumer.class);
        Mockito.verify(torrentDownloader).download(any(), any(), any(), captor.capture());
        return captor.getValue();
    }

    private StreamJobStatus status(String videoId) {
        return jobRepository.findById(videoId).map(StreamJob::status).orElseThrow();
    }

    // --- repair ---------------------------------------------------------------------------------

    private static final String BROKEN_MANIFEST = "no variant is playable outside Apple's platforms";

    /** A ladder whose media survived and whose playlists did not. */
    private void givenManifestOnlyFault() {
        given(mediaTranscoder.inspect(VIDEO_ID))
                .willReturn(new LadderReport(List.of(BROKEN_MANIFEST), true));
    }

    private static final String VIDEO_ID = "29dd7faa-3d34-48e2-abd4-732ed5b9abe5";

    @Test
    void repairingASoundVideoStartsNoWork() {
        given(mediaTranscoder.inspect(VIDEO_ID)).willReturn(LadderReport.sound());

        assertThat(service.repair(VIDEO_ID)).isEqualTo(RepairAction.NOTHING_TO_DO);

        Mockito.verify(mediaTranscoder, Mockito.never()).republish(any(), any());
        Mockito.verify(mediaTranscoder, Mockito.never()).transcodeToHls(any(), any(), any());
        Mockito.verify(torrentDownloader, Mockito.never()).download(any(), any(), any(), any());
    }

    @Test
    void rebuildsOnlyTheManifestsWhenTheMediaSurvived() {
        // The cheap tier, and the one that fixes a library published by a version of this service
        // that advertised a codec the browser refused. Re-encoding would spend minutes producing
        // bit-identical segments.
        givenManifestOnlyFault();
        given(mediaStorage.existingDownload(VIDEO_ID)).willReturn(Optional.of(VIDEO_FILE));
        given(mediaTranscoder.republish(VIDEO_FILE, VIDEO_ID)).willReturn(CompletableFuture.completedFuture(null));

        assertThat(service.repair(VIDEO_ID)).isEqualTo(RepairAction.MANIFESTS_REBUILT);

        Mockito.verify(mediaTranscoder).republish(VIDEO_FILE, VIDEO_ID);
        Mockito.verify(mediaTranscoder, Mockito.never()).transcodeToHls(any(), any(), any());
        // Nothing is discarded: those segments are the thing worth keeping.
        Mockito.verify(mediaStorage, Mockito.never()).discardIncompleteHls(any());
    }

    @Test
    void transcodesAgainWhenSegmentsAreGoneButTheDownloadIsNot() {
        given(mediaTranscoder.inspect(VIDEO_ID))
                .willReturn(new LadderReport(List.of("720p.m3u8 references 3 missing or empty file(s)"), false));
        given(mediaStorage.existingDownload(VIDEO_ID)).willReturn(Optional.of(VIDEO_FILE));
        given(mediaTranscoder.transcodeToHls(any(), any(), any())).willReturn(CompletableFuture.completedFuture(null));

        assertThat(service.repair(VIDEO_ID)).isEqualTo(RepairAction.RETRANSCODED);

        Mockito.verify(mediaStorage).discardIncompleteHls(VIDEO_ID);
        Mockito.verify(mediaTranscoder).transcodeToHls(any(), any(), any());
        Mockito.verify(torrentDownloader, Mockito.never()).download(any(), any(), any(), any());
    }

    @Test
    void fetchesTheTorrentAgainWhenTheDownloadIsGoneToo() {
        given(mediaTranscoder.inspect(VIDEO_ID))
                .willReturn(new LadderReport(List.of("720p.m3u8 is missing or unreadable"), false));
        given(mediaStorage.existingDownload(VIDEO_ID)).willReturn(Optional.empty());
        given(videoCatalog.sourceMagnetOf(VIDEO_ID)).willReturn(Optional.of(MAGNET));
        given(mediaStorage.downloadDirectoryFor(VIDEO_ID)).willReturn(Path.of("/downloads/x"));
        given(torrentDownloader.download(any(), any(), any(), any())).willReturn(new CompletableFuture<>());

        assertThat(service.repair(VIDEO_ID)).isEqualTo(RepairAction.REFETCHED);

        Mockito.verify(torrentDownloader).download(Mockito.eq(VIDEO_ID), Mockito.eq(MAGNET), any(), any());
    }

    @Test
    void refusesAVideoWithNothingLeftToRebuildItFrom() {
        // Broken media, no download, and a sidecar written before the magnet was ever recorded.
        // Nothing on this host knows where those bytes came from.
        given(mediaTranscoder.inspect(VIDEO_ID))
                .willReturn(new LadderReport(List.of("240p.m3u8 references 12 missing or empty file(s)"), false));
        given(mediaStorage.existingDownload(VIDEO_ID)).willReturn(Optional.empty());
        given(videoCatalog.sourceMagnetOf(VIDEO_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.repair(VIDEO_ID))
                .isInstanceOf(VideoNotRepairableException.class)
                .hasMessageContaining("no magnet was recorded");
    }

    @Test
    void aRepairIsFollowableOnTheSameEndpointAsAnIngestion() {
        // And under the same id. Re-ingesting would mint a second videoId and leave the first one
        // broken and listed; a viewer's link, the library card and the keep marker are all this id.
        givenManifestOnlyFault();
        given(mediaStorage.existingDownload(VIDEO_ID)).willReturn(Optional.of(VIDEO_FILE));
        given(mediaTranscoder.republish(any(), any())).willReturn(CompletableFuture.completedFuture(null));

        service.repair(VIDEO_ID);

        assertThat(jobRepository.findById(VIDEO_ID)).isPresent();
        assertThat(jobRepository.findById(VIDEO_ID).orElseThrow().status()).isEqualTo(StreamJobStatus.READY);
        assertThat(jobRepository.findById(VIDEO_ID).orElseThrow().videoId()).isEqualTo(VIDEO_ID);
    }

    @Test
    void aRepairThatFailsLeavesTheReasonOnTheJob() {
        givenManifestOnlyFault();
        given(mediaStorage.existingDownload(VIDEO_ID)).willReturn(Optional.of(VIDEO_FILE));
        given(mediaTranscoder.republish(any(), any()))
                .willReturn(CompletableFuture.failedFuture(new TranscodingException("still not publishable")));

        service.repair(VIDEO_ID);

        StreamJob job = jobRepository.findById(VIDEO_ID).orElseThrow();
        assertThat(job.status()).isEqualTo(StreamJobStatus.FAILED);
        assertThat(job.failureReason()).contains("still not publishable");
    }
}
