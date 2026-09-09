package com.azt.streaming.transcoding.infrastructure;

import com.azt.streaming.shared.config.AsyncConfiguration;
import com.azt.streaming.shared.config.StreamingProperties;
import com.azt.streaming.shared.storage.MediaStorage;
import com.azt.streaming.transcoding.domain.EncodedRendition;
import com.azt.streaming.transcoding.domain.HlsRendition;
import com.azt.streaming.transcoding.domain.MediaProbe;
import com.azt.streaming.transcoding.domain.MediaTranscoder;
import com.azt.streaming.transcoding.domain.ProbedVideo;
import com.azt.streaming.transcoding.domain.TranscodingException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Transcodes a source file into a CMAF HLS ladder with ffmpeg. */
@Slf4j
@Service
public class FfmpegMediaTranscoder implements MediaTranscoder {

    /** How far into the source to look for a poster frame before falling back to the start. */
    private static final Duration POSTER_SEEK = Duration.ofSeconds(5);

    private final MediaStorage mediaStorage;
    private final MediaProbe mediaProbe;
    private final FfmpegCommandBuilder commandBuilder;
    private final ProcessRunner processRunner;
    private final MasterPlaylistWriter masterPlaylistWriter;
    private final List<HlsRendition> ladder;
    private final Duration timeout;
    private final MeterRegistry meterRegistry;

    public FfmpegMediaTranscoder(
            MediaStorage mediaStorage,
            MediaProbe mediaProbe,
            FfmpegCommandBuilder commandBuilder,
            ProcessRunner processRunner,
            MasterPlaylistWriter masterPlaylistWriter,
            MeterRegistry meterRegistry,
            StreamingProperties properties) {
        this.mediaStorage = mediaStorage;
        this.mediaProbe = mediaProbe;
        this.commandBuilder = commandBuilder;
        this.processRunner = processRunner;
        this.masterPlaylistWriter = masterPlaylistWriter;
        this.meterRegistry = meterRegistry;
        this.timeout = properties.ffmpeg().timeout();
        this.ladder = properties.ffmpeg().renditions().stream()
                .map(r -> new HlsRendition(r.name(), r.width(), r.height(), r.videoBitrateKbps(), r.audioBitrateKbps()))
                .toList();
    }

    @Override
    @Async(AsyncConfiguration.TRANSCODING_EXECUTOR)
    public CompletableFuture<Void> transcodeToHls(Path inputFile, String videoId) {
        log.info("Transcoding videoId {} from {}", videoId, inputFile);

        // Timed because the ladder went from two sequential rungs to five in one pass: whether that
        // trade is paying off is a question about wall-clock time per encode, and there was no way
        // to answer it. Tagged by outcome so a fast failure cannot be mistaken for a fast success.
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "failure";

        Path videoDirectory = mediaStorage.hlsDirectoryFor(videoId);
        try {
            // Probed before encoding for one decision that cannot be guessed: whether to map an
            // audio track. `-map a:0` against a file that has none fails the entire encode, and a
            // torrent is not a file we chose.
            ProbedVideo source = mediaProbe.probe(inputFile);
            if (!source.hasVideo()) {
                throw new TranscodingException("No video stream in " + inputFile);
            }
            if (!source.hasAudio()) {
                log.warn("Source for videoId {} has no audio track; encoding video only", videoId);
            }

            // One invocation for the whole ladder. The previous version ran one per rung, which
            // decoded the source once per rung.
            processRunner.run(commandBuilder.build(inputFile, videoDirectory, ladder, source.hasAudio()), timeout);

            List<EncodedRendition> encoded = measure(videoDirectory, source.hasAudio());

            // Before the master playlist, so a video is never listed without the thumbnail the
            // library expects to draw beside it.
            writePoster(inputFile, videoDirectory, videoId);

            // Written last, so its presence is the signal that the whole ladder is ready. Playback
            // 404s until this exists, which is exactly the behaviour the player expects.
            masterPlaylistWriter.write(videoDirectory, encoded);

            log.info("Transcoding complete for videoId {}", videoId);
            outcome = "success";
            return CompletableFuture.completedFuture(null);
        } catch (IOException e) {
            throw new TranscodingException("Failed to write HLS output for videoId " + videoId, e);
        } finally {
            sample.stop(Timer.builder("aztcast.transcode")
                    .description("Wall-clock time to encode a full ladder")
                    .tag("outcome", outcome)
                    .tag("rungs", String.valueOf(ladder.size()))
                    .register(meterRegistry));
        }
    }

    /**
     * Extracts the poster frame, and never fails the ingestion over it.
     *
     * <p>Two attempts. The first seeks in, because the opening seconds of a real file are titles,
     * logos or black; the second starts from the beginning, because nothing here knows the source's
     * duration and a clip shorter than the offset fails the seek outright. A video with no readable
     * frame at all is still a video, so the third outcome is a warning and no poster.
     */
    private void writePoster(Path inputFile, Path videoDirectory, String videoId) {
        Path poster = videoDirectory.resolve(MediaStorage.POSTER);
        for (Duration seek : List.of(POSTER_SEEK, Duration.ZERO)) {
            try {
                processRunner.run(
                        commandBuilder.buildPoster(inputFile, poster, seek.isZero() ? null : seek), timeout);
                return;
            } catch (RuntimeException e) {
                log.debug("Poster attempt at {} failed for videoId {}", seek, videoId, e);
            }
        }
        log.warn("No poster frame could be extracted for videoId {}; the library will show a placeholder", videoId);
    }

    /**
     * Reads back what the encoder actually produced, per rung, to build the CODECS attributes.
     *
     * <p>Probing the variant playlist rather than a segment is deliberate: a bare {@code .m4s}
     * carries no codec configuration and the {@code _init.mp4} alone reports level {@code -99},
     * because the profile and level live in the bitstream's SPS. ffprobe follows the playlist's
     * {@code EXT-X-MAP} and its first segment, and reports what a player would actually see.
     *
     * <p>A probe failure degrades to the previously hardcoded string rather than failing the
     * ingestion: an approximate CODECS on a playable ladder beats no ladder at all.
     */
    private List<EncodedRendition> measure(Path videoDirectory, boolean sourceHadAudio) {
        List<EncodedRendition> encoded = new ArrayList<>(ladder.size());
        for (HlsRendition rendition : ladder) {
            Path variantPlaylist = videoDirectory.resolve(rendition.playlistFileName());
            try {
                ProbedVideo output = mediaProbe.probe(variantPlaylist);
                encoded.add(new EncodedRendition(rendition, output.codecs()));
            } catch (RuntimeException e) {
                String fallback = sourceHadAudio ? ProbedVideo.FALLBACK_CODECS : "avc1.4d001f";
                log.warn(
                        "Could not probe {} for videoId directory {}; advertising {}",
                        rendition.playlistFileName(),
                        videoDirectory.getFileName(),
                        fallback,
                        e);
                encoded.add(new EncodedRendition(rendition, fallback));
            }
        }
        return encoded;
    }
}
