package com.azt.streaming.transcoding.infrastructure;

import com.azt.streaming.shared.config.AsyncConfiguration;
import com.azt.streaming.shared.config.StreamingProperties;
import com.azt.streaming.transcoding.domain.HlsRendition;
import com.azt.streaming.transcoding.domain.MediaTranscoder;
import com.azt.streaming.transcoding.domain.TranscodingException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Transcodes a media file into an HLS ladder by shelling out to ffmpeg, one invocation per rung.
 *
 * <p>What used to be a 90-line method is now composition: {@link FfmpegCommandBuilder} decides the
 * arguments, {@link ProcessRunner} owns the subprocess lifecycle, {@link MasterPlaylistWriter}
 * renders the manifest, and {@link HlsRendition} carries the encoder arithmetic. Each of those is
 * unit-testable on its own; the 90-line version was not testable at all.
 */
@Service
@Slf4j
public class FfmpegMediaTranscoder implements MediaTranscoder {

    private final Path hlsRoot;
    private final Duration timeout;
    private final List<HlsRendition> ladder;
    private final FfmpegCommandBuilder commandBuilder;
    private final ProcessRunner processRunner;
    private final MasterPlaylistWriter masterPlaylistWriter;

    public FfmpegMediaTranscoder(
            StreamingProperties properties,
            FfmpegCommandBuilder commandBuilder,
            ProcessRunner processRunner,
            MasterPlaylistWriter masterPlaylistWriter) {
        this.hlsRoot = properties.storage().hlsDir();
        this.timeout = properties.ffmpeg().timeout();
        this.ladder = properties.ffmpeg().renditions().stream().map(FfmpegMediaTranscoder::toRendition).toList();
        this.commandBuilder = commandBuilder;
        this.processRunner = processRunner;
        this.masterPlaylistWriter = masterPlaylistWriter;
    }

    @Override
    @Async(AsyncConfiguration.TRANSCODING_EXECUTOR)
    public CompletableFuture<Void> transcodeToHls(Path inputFile, String videoId) {
        log.info("Transcoding videoId {} from {}", videoId, inputFile);

        Path videoDirectory = hlsRoot.resolve(videoId);
        try {
            Files.createDirectories(videoDirectory);

            for (HlsRendition rendition : ladder) {
                log.info("Encoding {} for videoId {}", rendition.name(), videoId);
                processRunner.run(commandBuilder.build(inputFile, videoDirectory, rendition), timeout);
            }

            // Written last, so its presence is the signal that the whole ladder is ready. Playback
            // 404s until this exists, which is exactly the behaviour the player expects.
            masterPlaylistWriter.write(videoDirectory, ladder);

            log.info("Transcoding complete for videoId {}", videoId);
            return CompletableFuture.completedFuture(null);
        } catch (IOException e) {
            throw new TranscodingException("Failed to write HLS output for videoId " + videoId, e);
        }
    }

    private static HlsRendition toRendition(StreamingProperties.Rendition rendition) {
        return new HlsRendition(
                rendition.name(),
                rendition.width(),
                rendition.height(),
                rendition.videoBitrateKbps(),
                rendition.audioBitrateKbps());
    }
}
