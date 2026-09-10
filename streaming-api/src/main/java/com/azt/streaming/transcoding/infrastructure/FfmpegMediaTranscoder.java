package com.azt.streaming.transcoding.infrastructure;

import com.azt.streaming.shared.config.AsyncConfiguration;
import com.azt.streaming.shared.config.StreamingProperties;
import com.azt.streaming.shared.storage.MediaStorage;
import com.azt.streaming.transcoding.domain.AudioPlan;
import com.azt.streaming.transcoding.domain.EncodedAudio;
import com.azt.streaming.transcoding.domain.EncodedRendition;
import com.azt.streaming.transcoding.domain.HlsRendition;
import com.azt.streaming.transcoding.domain.LadderReport;
import com.azt.streaming.transcoding.domain.MediaProbe;
import com.azt.streaming.transcoding.domain.MediaTranscoder;
import com.azt.streaming.transcoding.domain.PlannedRendition;
import com.azt.streaming.transcoding.domain.ProbedSource;
import com.azt.streaming.transcoding.domain.ProbedVideo;
import com.azt.streaming.transcoding.domain.SubtitlePlan;
import com.azt.streaming.transcoding.domain.TranscodePlan;
import com.azt.streaming.transcoding.domain.TranscodingException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Transcodes a source file into a CMAF HLS ladder with ffmpeg. */
@Slf4j
@Service
public class FfmpegMediaTranscoder implements MediaTranscoder {

    /** How far into the source to look for a poster frame before falling back to the start. */
    private static final Duration POSTER_SEEK = Duration.ofSeconds(5);

    /**
     * A poster is one frame. Giving it the ladder's thirty-minute deadline meant a source that hung
     * the decoder could spend an hour on thumbnails after the ladder had already succeeded.
     */
    private static final Duration POSTER_TIMEOUT = Duration.ofMinutes(2);

    private final MediaStorage mediaStorage;
    private final MediaProbe mediaProbe;
    private final TranscodePlanner planner;
    private final FfmpegCommandBuilder commandBuilder;
    private final ProcessRunner processRunner;
    private final MasterPlaylistWriter masterPlaylistWriter;
    private final LadderIntegrity ladderIntegrity;
    private final SubtitlePublisher subtitlePublisher;
    private final VariantWeigher variantWeigher;
    private final Duration timeout;
    private final MeterRegistry meterRegistry;

    public FfmpegMediaTranscoder(
            MediaStorage mediaStorage,
            MediaProbe mediaProbe,
            TranscodePlanner planner,
            FfmpegCommandBuilder commandBuilder,
            ProcessRunner processRunner,
            MasterPlaylistWriter masterPlaylistWriter,
            LadderIntegrity ladderIntegrity,
            SubtitlePublisher subtitlePublisher,
            VariantWeigher variantWeigher,
            MeterRegistry meterRegistry,
            StreamingProperties properties) {
        this.mediaStorage = mediaStorage;
        this.mediaProbe = mediaProbe;
        this.planner = planner;
        this.commandBuilder = commandBuilder;
        this.processRunner = processRunner;
        this.masterPlaylistWriter = masterPlaylistWriter;
        this.ladderIntegrity = ladderIntegrity;
        this.subtitlePublisher = subtitlePublisher;
        this.variantWeigher = variantWeigher;
        this.meterRegistry = meterRegistry;
        this.timeout = properties.ffmpeg().timeout();
    }

    @Override
    @Async(AsyncConfiguration.TRANSCODING_EXECUTOR)
    public CompletableFuture<Void> transcodeToHls(Path inputFile, String videoId, IntConsumer onProgress) {
        log.info("Transcoding videoId {} from {}", videoId, inputFile);

        // Timed because the ladder went from two sequential rungs to five in one pass: whether that
        // trade is paying off is a question about wall-clock time per encode, and there was no way
        // to answer it. Tagged by outcome so a fast failure cannot be mistaken for a fast success.
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "failure";
        // Tagged with what was actually built rather than what was configured, now that those are
        // no longer the same number: a 480p source builds a shorter ladder than a 1080p one, and
        // comparing their encode times without that tag compares two different jobs.
        int rungs = 0;

        Path videoDirectory = mediaStorage.hlsDirectoryFor(videoId);
        try {
            ProbedSource source = mediaProbe.probeSource(inputFile);
            if (!source.hasVideo()) {
                throw new TranscodingException("No video stream in " + inputFile);
            }

            // Every decision about this file, made here, from the probe and from what this ffmpeg
            // build can actually do. Whatever is impossible on this host is impossible now, in a
            // sentence, rather than in forty lines of stack trace half an hour from now.
            TranscodePlan plan = planner.plan(source);
            rungs = plan.renditions().size();
            log.info("Plan for videoId {}: {}", videoId, plan.describe());
            if (plan.audio().reason() != null) {
                log.warn("Audio for videoId {}: {}", videoId, plan.audio().reason());
            }

            // One invocation for the whole ladder. The previous version ran one per rung, which
            // decoded the source once per rung.
            processRunner.run(
                    commandBuilder.build(inputFile, videoDirectory, plan, planner.encoder()),
                    timeout,
                    new FfmpegProgress(source.durationSeconds(), onProgress));

            publish(inputFile, videoDirectory, videoId, plan, source);

            log.info("Transcoding complete for videoId {}", videoId);
            outcome = "success";
            return CompletableFuture.completedFuture(null);
        } catch (IOException e) {
            throw new TranscodingException("Failed to write HLS output for videoId " + videoId, e);
        } finally {
            if (!"success".equals(outcome)) {
                // A failed encode leaves partial segments, variant playlists for rungs that never
                // finished, and no master playlist — invisible to the library, which lists only
                // directories that have one, and charged to the disk until the reaper came for it a
                // week later. Storage refuses to touch a directory that does have a master, so this
                // can only ever remove something nothing could play.
                mediaStorage.discardIncompleteHls(videoId);
            }
            sample.stop(Timer.builder("aztcast.transcode")
                    .description("Wall-clock time to encode a full ladder")
                    .tag("outcome", outcome)
                    .tag("rungs", String.valueOf(rungs))
                    .register(meterRegistry));
        }
    }

    @Override
    public LadderReport inspect(String videoId) {
        return ladderIntegrity.inspectPublished(mediaStorage.hlsDirectoryFor(videoId));
    }

    @Override
    @Async(AsyncConfiguration.TRANSCODING_EXECUTOR)
    public CompletableFuture<Void> republish(Path inputFile, String videoId) {
        log.info("Rebuilding the manifests for videoId {} from the media already on disk", videoId);
        Path videoDirectory = mediaStorage.hlsDirectoryFor(videoId);

        ProbedSource source = mediaProbe.probeSource(inputFile);
        if (!source.hasVideo()) {
            throw new TranscodingException("No video stream in " + inputFile);
        }
        // The same plan the ladder was built from, so the audio and subtitle renditions are
        // described the same way. Only the encode is skipped.
        TranscodePlan plan = planner.plan(source);
        if (plan.audio().reason() != null) {
            log.warn("Audio for videoId {}: {}", videoId, plan.audio().reason());
        }
        try {
            publish(inputFile, videoDirectory, videoId, plan, source);
        } catch (IOException e) {
            throw new TranscodingException("Failed to write HLS output for videoId " + videoId, e);
        }
        log.info("Manifests rebuilt for videoId {}", videoId);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Everything after the encode: subtitles, measurement, poster, integrity check, master playlist.
     *
     * <p>Shared with {@link #republish} because rebuilding a manifest is exactly this and nothing
     * else. Keeping one copy is what stops the repaired ladder from being described differently
     * from the one the encode produced.
     */
    private void publish(
            Path inputFile, Path videoDirectory, String videoId, TranscodePlan plan, ProbedSource source)
            throws IOException {
        List<SubtitlePlan> subtitles =
                subtitlePublisher.publish(inputFile, videoDirectory, plan.subtitles(), source.durationSeconds());
        EncodedAudio audio = measureAudio(videoDirectory, plan.audio());
        List<EncodedRendition> encoded = measure(videoDirectory, plan.renditions());

        // Before the master playlist, so a video is never listed without the thumbnail the library
        // expects to draw beside it.
        if (!Files.isRegularFile(videoDirectory.resolve(MediaStorage.POSTER))) {
            writePoster(inputFile, videoDirectory, videoId);
        }

        // Checked before it is announced. The master playlist is the readiness sentinel — the
        // catalogue lists a video if and only if this file exists — and it used to be written on
        // the strength of ffmpeg exiting zero, which proves neither that the segments the playlists
        // name are on disk nor that a browser can assemble any of them.
        String master = masterPlaylistWriter.render(encoded, audio, subtitles);
        List<String> problems = ladderIntegrity.verify(videoDirectory, master, encoded, audio, subtitles);
        if (!problems.isEmpty()) {
            throw new TranscodingException("The ladder for videoId %s is not publishable:%n  - %s"
                    .formatted(videoId, String.join("%n  - ".formatted(), problems)));
        }

        // Written last, so its presence is the signal that the whole ladder is ready. Playback 404s
        // until this exists, which is exactly the behaviour the player expects.
        masterPlaylistWriter.write(videoDirectory, master);
    }

    /**
     * Extracts the poster frame, and never fails the ingestion over it.
     *
     * <p>Two attempts. The first seeks in, because the opening seconds of a real file are titles,
     * logos or black; the second starts from the beginning, because a clip shorter than the offset
     * fails the seek outright. A video with no readable frame at all is still a video, so the third
     * outcome is a warning and no poster.
     */
    private void writePoster(Path inputFile, Path videoDirectory, String videoId) {
        Path poster = videoDirectory.resolve(MediaStorage.POSTER);
        for (Duration seek : List.of(POSTER_SEEK, Duration.ZERO)) {
            try {
                processRunner.run(
                        commandBuilder.buildPoster(inputFile, poster, seek.isZero() ? null : seek), POSTER_TIMEOUT);
                return;
            } catch (RuntimeException e) {
                log.debug("Poster attempt at {} failed for videoId {}", seek, videoId, e);
            }
        }
        log.warn("No poster frame could be extracted for videoId {}; the library will show a placeholder", videoId);
    }

    /**
     * Reads back what the encoder actually produced, per rung: its codecs and its real bitrate.
     *
     * <p>Probing the variant playlist rather than a segment is deliberate: a bare {@code .m4s}
     * carries no codec configuration and the {@code _init.mp4} alone reports level {@code -99},
     * because the profile and level live in the bitstream's SPS. ffprobe follows the playlist's
     * {@code EXT-X-MAP} and its first segment, and reports what a player would actually see.
     *
     * <p>A probe failure degrades to the previously hardcoded string rather than failing the
     * ingestion: an approximate CODECS on a playable ladder beats no ladder at all.
     *
     * <p>Measuring matters more for a copied rung than for an encoded one, not less: nothing here
     * chose its profile, level or bitrate, so the source's own High@4.1 — which plenty of devices
     * refuse — would otherwise be advertised as whatever the ladder assumed.
     */
    private List<EncodedRendition> measure(Path videoDirectory, List<PlannedRendition> planned) {
        List<EncodedRendition> encoded = new ArrayList<>(planned.size());
        for (PlannedRendition rung : planned) {
            HlsRendition rendition = rung.rendition();
            Path variantPlaylist = videoDirectory.resolve(rendition.playlistFileName());
            String codecs = ProbedVideo.FALLBACK_CODECS;
            try {
                codecs = mediaProbe.probe(variantPlaylist).codecs();
            } catch (RuntimeException e) {
                log.warn(
                        "Could not probe {} in {}; advertising {}",
                        rendition.playlistFileName(),
                        videoDirectory.getFileName(),
                        codecs,
                        e);
            }
            String finalCodecs = codecs;
            encoded.add(variantWeigher
                    .weigh(variantPlaylist)
                    .map(weight -> new EncodedRendition(
                            rendition, finalCodecs, weight.averageBps(), weight.peakBps()))
                    .orElseGet(() -> EncodedRendition.estimated(rendition, finalCodecs)));
        }
        return encoded;
    }

    /** The shared audio rendition, weighed the same way, because a copied track declares nothing. */
    private EncodedAudio measureAudio(Path videoDirectory, AudioPlan audio) {
        if (!audio.present()) {
            return EncodedAudio.none();
        }
        return variantWeigher
                .weigh(videoDirectory.resolve(audio.playlistFileName()))
                .map(weight -> new EncodedAudio(audio, weight.averageBps(), weight.peakBps()))
                .orElseGet(() -> new EncodedAudio(audio, audio.bitrateKbps() * 1000, audio.bitrateKbps() * 1000));
    }
}
