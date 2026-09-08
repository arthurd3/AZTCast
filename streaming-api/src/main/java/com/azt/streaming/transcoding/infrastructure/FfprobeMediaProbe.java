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
                "-show_entries", "stream=codec_type,profile,level",
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
            JsonNode streams = objectMapper.readTree(json).path("streams");
            JsonNode video = null;
            boolean hasAudio = false;
            for (JsonNode stream : streams) {
                String type = stream.path("codec_type").asText();
                if ("video".equals(type) && video == null) {
                    video = stream;
                } else if ("audio".equals(type)) {
                    hasAudio = true;
                }
            }
            if (video == null) {
                return new ProbedVideo(false, hasAudio, null, 0);
            }
            return new ProbedVideo(true, hasAudio, video.path("profile").asText(null), video.path("level").asInt());
        } catch (Exception e) {
            throw new TranscodingException("Could not parse ffprobe output for " + file, e);
        }
    }
}
