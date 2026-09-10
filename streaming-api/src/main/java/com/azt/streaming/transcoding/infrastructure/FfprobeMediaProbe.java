package com.azt.streaming.transcoding.infrastructure;

import com.azt.streaming.shared.config.StreamingProperties;
import com.azt.streaming.transcoding.domain.MediaProbe;
import com.azt.streaming.transcoding.domain.ProbedVideo;
import com.azt.streaming.transcoding.domain.TranscodingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@link MediaProbe} backed by the ffprobe binary.
 *
 * <p>{@code probe-binary} has been configured, validated at startup and never called since it was
 * introduced. This is its first consumer.
 */
@Component
public class FfprobeMediaProbe implements MediaProbe {

    /**
     * Probing reads container headers, not frames, so it finishes in milliseconds on any sane input.
     * A separate, short deadline from {@code ffmpeg.timeout} (30 minutes) because waiting half an
     * hour to learn a file is unreadable is not a useful behaviour.
     */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);

    private final String probeBinary;
    private final ProcessRunner processRunner;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FfprobeMediaProbe(StreamingProperties properties, ProcessRunner processRunner) {
        this.probeBinary = properties.ffmpeg().probeBinary();
        this.processRunner = processRunner;
    }

    @Override
    public ProbedVideo probe(Path file) {
        String json = processRunner.runCapturing(command(file), PROBE_TIMEOUT);
        return parse(json, file);
    }

    List<String> command(Path file) {
        return List.of(
                probeBinary,
                // Quiet, so anything on stderr is a real diagnostic rather than a banner.
                "-v", "error",
                // The format-level bitrate rather than the stream-level one: Matroska routinely
                // omits per-stream bit_rate, and a container total is close enough for the
                // BANDWIDTH attribute of a rung that is copied rather than rate-controlled.
                "-show_entries", "stream=codec_type,codec_name,profile,level,width,height:format=bit_rate,duration,size",
                "-of", "json",
                file.toString());
    }

    private ProbedVideo parse(String json, Path file) {
        // A zero-exit ffprobe that printed nothing means the arguments changed under us or the
        // binary is a stub. Saying that is more useful than letting the JSON parser complain about
        // null input two frames later.
        if (json == null || json.isBlank()) {
            throw new TranscodingException("ffprobe produced no output for " + file);
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode streams = root.path("streams");
            JsonNode video = null;
            JsonNode audio = null;
            for (JsonNode stream : streams) {
                String type = stream.path("codec_type").asText();
                if ("video".equals(type) && video == null) {
                    video = stream;
                } else if ("audio".equals(type) && audio == null) {
                    audio = stream;
                }
            }

            boolean hasAudio = audio != null;
            String audioCodec = hasAudio ? audio.path("codec_name").asText(null) : null;
            String audioProfile = hasAudio ? audio.path("profile").asText(null) : null;
            int bitRateKbps = bitRateKbps(root);

            if (video == null) {
                return new ProbedVideo(false, hasAudio, null, 0, null, audioCodec, audioProfile, 0, 0, bitRateKbps);
            }
            return new ProbedVideo(
                    true,
                    hasAudio,
                    video.path("profile").asText(null),
                    video.path("level").asInt(),
                    video.path("codec_name").asText(null),
                    audioCodec,
                    audioProfile,
                    video.path("width").asInt(),
                    video.path("height").asInt(),
                    bitRateKbps);
        } catch (Exception e) {
            throw new TranscodingException("Could not parse ffprobe output for " + file, e);
        }
    }

    /**
     * Container bitrate in kbps, or 0 when the file does not declare one.
     *
     * <p>Absent often enough to be the normal case rather than an error: a Matroska file written by
     * a muxer that did not bother, or a playlist probed rather than a file, both report nothing.
     * Zero is the caller's signal to derive a number some other way rather than trust this one.
     */
    private static int bitRateKbps(JsonNode root) {
        JsonNode format = root.path("format");
        long bitsPerSecond = format.path("bit_rate").asLong(0);
        if (bitsPerSecond > 0) {
            return (int) (bitsPerSecond / 1000);
        }

        // Matroska written by a muxer that did not bother is the normal case, not an error, and it
        // is also the container most torrents arrive in. Size over duration is what the declared
        // figure would have been anyway.
        double seconds = format.path("duration").asDouble(0);
        long bytes = format.path("size").asLong(0);
        if (seconds <= 0 || bytes <= 0) {
            return 0;
        }
        return (int) (bytes * 8 / seconds / 1000);
    }
}
