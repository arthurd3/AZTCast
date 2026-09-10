package com.azt.streaming.transcoding.infrastructure;

import com.azt.streaming.shared.config.StreamingProperties;
import com.azt.streaming.transcoding.domain.MediaProbe;
import com.azt.streaming.transcoding.domain.ProbedAudio;
import com.azt.streaming.transcoding.domain.ProbedSource;
import com.azt.streaming.transcoding.domain.ProbedSubtitle;
import com.azt.streaming.transcoding.domain.ProbedVideo;
import com.azt.streaming.transcoding.domain.TranscodingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * {@link MediaProbe} backed by the ffprobe binary.
 *
 * <p>Two questions, two commands. Measuring a rung this service wrote needs three fields; describing
 * a file that arrived from a swarm needs every stream it carries, with its language tags and
 * dispositions. Asking the larger question of a variant playlist would work and would be waste;
 * asking the smaller one of a source is what left the pipeline unable to see that its audio was
 * 5.1 E-AC-3 until ffmpeg refused to decode it.
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
        JsonNode root = read(command(file), file);
        JsonNode video = firstStreamOfType(root, "video");
        boolean hasAudio = firstStreamOfType(root, "audio") != null;
        if (video == null) {
            return new ProbedVideo(false, hasAudio, null, 0);
        }
        return new ProbedVideo(true, hasAudio, video.path("profile").asText(null), video.path("level").asInt());
    }

    @Override
    public ProbedSource probeSource(Path file) {
        JsonNode root = read(sourceCommand(file), file);
        JsonNode video = null;
        List<ProbedAudio> audio = new ArrayList<>();
        List<ProbedSubtitle> subtitles = new ArrayList<>();

        for (JsonNode stream : root.path("streams")) {
            switch (stream.path("codec_type").asText()) {
                case "video" -> {
                    if (video == null) {
                        video = stream;
                    }
                }
                // The ordinal among streams of this type, not the container index: `-map a:1` counts
                // audio streams, and on a file whose audio sits at container index 1 those two
                // numbers agree right up until a release ships two video streams or a cover image.
                case "audio" -> audio.add(audioTrack(stream, audio.size()));
                case "subtitle" -> subtitles.add(subtitleTrack(stream, subtitles.size()));
                default -> {
                    // Data and attachment streams. Nothing here maps them, deliberately.
                }
            }
        }

        JsonNode format = root.path("format");
        if (video == null) {
            return new ProbedSource(false, null, null, 0, 0, 0, 0, bitRateKbps(format), duration(format), audio, subtitles);
        }
        return new ProbedSource(
                true,
                video.path("codec_name").asText(null),
                video.path("profile").asText(null),
                video.path("level").asInt(),
                video.path("width").asInt(),
                video.path("height").asInt(),
                frameRate(video.path("r_frame_rate").asText(null)),
                bitRateKbps(format),
                duration(format),
                audio,
                subtitles);
    }

    /** The three fields a CODECS string needs, and nothing else. */
    List<String> command(Path file) {
        return List.of(
                probeBinary,
                // Quiet, so anything on stderr is a real diagnostic rather than a banner.
                "-v", "error",
                "-show_entries", "stream=codec_type,profile,level",
                "-of", "json",
                file.toString());
    }

    /** Everything a decision could turn on. */
    List<String> sourceCommand(Path file) {
        return List.of(
                probeBinary,
                "-v", "error",
                "-show_entries",
                        "stream=index,codec_type,codec_name,profile,level,width,height,channels,sample_rate,r_frame_rate"
                                + ":stream_tags=language,title"
                                + ":stream_disposition=default,forced,hearing_impaired"
                                // The format-level bitrate rather than the stream-level one:
                                // Matroska routinely omits per-stream bit_rate, and a container
                                // total is close enough for a first estimate of a copied rung.
                                + ":format=bit_rate,duration,size",
                "-of", "json",
                file.toString());
    }

    private JsonNode read(List<String> command, Path file) {
        String json = processRunner.runCapturing(command, PROBE_TIMEOUT);
        // A zero-exit ffprobe that printed nothing means the arguments changed under us or the
        // binary is a stub. Saying that is more useful than letting the JSON parser complain about
        // null input two frames later.
        if (json == null || json.isBlank()) {
            throw new TranscodingException("ffprobe produced no output for " + file);
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new TranscodingException("Could not parse ffprobe output for " + file, e);
        }
    }

    /**
     * The first stream of a type, read from the top-level {@code streams} array only.
     *
     * <p>Deliberately not {@code programs[].streams}: ffprobe duplicates every stream under both
     * keys when it is handed a playlist, and walking both counts each one twice.
     */
    private static JsonNode firstStreamOfType(JsonNode root, String type) {
        for (JsonNode stream : root.path("streams")) {
            if (type.equals(stream.path("codec_type").asText())) {
                return stream;
            }
        }
        return null;
    }

    private static ProbedAudio audioTrack(JsonNode stream, int ordinal) {
        JsonNode tags = stream.path("tags");
        return new ProbedAudio(
                ordinal,
                stream.path("codec_name").asText(null),
                stream.path("profile").asText(null),
                stream.path("channels").asInt(),
                stream.path("sample_rate").asInt(),
                tags.path("language").asText(null),
                tags.path("title").asText(null),
                stream.path("disposition").path("default").asInt() == 1);
    }

    private static ProbedSubtitle subtitleTrack(JsonNode stream, int ordinal) {
        JsonNode tags = stream.path("tags");
        JsonNode disposition = stream.path("disposition");
        return new ProbedSubtitle(
                ordinal,
                stream.path("codec_name").asText(null),
                tags.path("language").asText(null),
                tags.path("title").asText(null),
                disposition.path("forced").asInt() == 1,
                disposition.path("hearing_impaired").asInt() == 1);
    }

    /**
     * {@code r_frame_rate} as a number, or 0 when it is not one.
     *
     * <p>ffprobe reports it as a rational — {@code 24000/1001} for 23.976 — and reports {@code 0/0}
     * for streams that have no frame rate at all, which is every audio and subtitle track. Zero
     * propagates to "emit no {@code -g}", which is the safe answer: keyframes are forced by time
     * regardless, and a GOP length derived from a frame rate nobody reported would be a guess
     * applied to every frame.
     */
    private static double frameRate(String rational) {
        if (rational == null || rational.isBlank()) {
            return 0;
        }
        String[] parts = rational.split("/", 2);
        try {
            double numerator = Double.parseDouble(parts[0]);
            double denominator = parts.length == 2 ? Double.parseDouble(parts[1]) : 1;
            return denominator == 0 ? 0 : numerator / denominator;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double duration(JsonNode format) {
        return Math.max(format.path("duration").asDouble(0), 0);
    }

    /**
     * Container bitrate in kbps, or 0 when the file does not declare one.
     *
     * <p>Absent often enough to be the normal case rather than an error: a Matroska file written by
     * a muxer that did not bother, or a playlist probed rather than a file, both report nothing.
     * Zero is the caller's signal to derive a number some other way rather than trust this one.
     */
    private static int bitRateKbps(JsonNode format) {
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
