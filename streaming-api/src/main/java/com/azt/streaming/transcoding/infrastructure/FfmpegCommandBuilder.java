package com.azt.streaming.transcoding.infrastructure;

import com.azt.streaming.shared.config.StreamingProperties;
import com.azt.streaming.transcoding.domain.HlsRendition;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Builds the ffmpeg argument list for one rung of the ladder.
 *
 * <p>Deliberately a pure function of its inputs, so the command can be asserted in a unit test
 * without a video file, a subprocess or a Spring context. The encoder flags are unchanged from the
 * inline version; what changed is that the binary path and segment duration are configuration
 * rather than literals, and the arguments are no longer assembled from parallel arrays.
 */
@Component
public class FfmpegCommandBuilder {

    private final String binary;
    private final String videoCodec;
    private final Duration segmentDuration;

    public FfmpegCommandBuilder(StreamingProperties properties) {
        this.binary = properties.ffmpeg().binary();
        this.videoCodec = properties.ffmpeg().videoCodec();
        this.segmentDuration = properties.ffmpeg().segmentDuration();
    }

    /**
     * @param inputFile source media
     * @param outputDirectory folder that will hold this rung's playlist and segments
     * @param rendition the rung to encode
     */
    public List<String> build(Path inputFile, Path outputDirectory, HlsRendition rendition) {
        return List.of(
                binary,
                "-i", inputFile.toString(),
                "-c:v", videoCodec,
                "-profile:v", "main",
                "-level", "3.1",
                "-preset", "veryfast",
                "-s", rendition.resolution(),
                "-b:v", kbps(rendition.videoBitrateKbps()),
                "-maxrate", kbps(rendition.maxrateKbps()),
                "-bufsize", kbps(rendition.bufsizeKbps()),
                "-c:a", "aac",
                "-b:a", kbps(rendition.audioBitrateKbps()),
                "-ac", "2",
                "-ar", "48000",
                "-f", "hls",
                "-hls_time", String.valueOf(segmentDuration.toSeconds()),
                "-hls_playlist_type", "vod",
                "-hls_segment_filename",
                outputDirectory.resolve(rendition.segmentPattern()).toString(),
                outputDirectory.resolve(rendition.playlistFileName()).toString());
    }

    private static String kbps(int value) {
        return value + "k";
    }
}
