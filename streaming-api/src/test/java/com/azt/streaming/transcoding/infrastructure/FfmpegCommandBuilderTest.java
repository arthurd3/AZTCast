package com.azt.streaming.transcoding.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.azt.streaming.support.PropertiesFixture;
import com.azt.streaming.transcoding.domain.HlsRendition;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class FfmpegCommandBuilderTest {

    private static final HlsRendition RENDITION_720P = new HlsRendition("720p", 1280, 720, 3000, 128);

    private final FfmpegCommandBuilder builder =
            new FfmpegCommandBuilder(PropertiesFixture.defaults().binary("ffmpeg").build());

    @Test
    void putsTheConfiguredBinaryFirstAndTheOutputPlaylistLast() {
        List<String> command = builder.build(Path.of("/in/movie.mkv"), Path.of("/out/vid"), RENDITION_720P);

        assertThat(command.getFirst()).isEqualTo("ffmpeg");
        assertThat(command.getLast()).isEqualTo("/out/vid/720p.m3u8");
    }

    @Test
    void derivesMaxrateAndBufsizeFromTheVideoBitrate() {
        List<String> command = builder.build(Path.of("in.mkv"), Path.of("out"), RENDITION_720P);

        assertThat(valueOf(command, "-b:v")).isEqualTo("3000k");
        assertThat(valueOf(command, "-maxrate")).isEqualTo("3210k"); // 3000 * 1.07
        assertThat(valueOf(command, "-bufsize")).isEqualTo("4500k"); // 3000 * 1.5
    }

    @Test
    void scalesToTheRenditionResolution() {
        List<String> command = builder.build(Path.of("in.mkv"), Path.of("out"), RENDITION_720P);

        assertThat(valueOf(command, "-s")).isEqualTo("1280x720");
    }

    @Test
    void usesTheConfiguredSegmentDuration() {
        FfmpegCommandBuilder tenSecond =
                new FfmpegCommandBuilder(
                        PropertiesFixture.defaults().segmentDuration(Duration.ofSeconds(10)).build());

        assertThat(valueOf(tenSecond.build(Path.of("in.mkv"), Path.of("out"), RENDITION_720P), "-hls_time"))
                .isEqualTo("10");
    }

    @Test
    void namesSegmentsPerRenditionSoRungsCannotCollide() {
        Path out = Path.of("/out/vid");
        HlsRendition low = new HlsRendition("240p", 426, 240, 800, 128);

        assertThat(valueOf(builder.build(Path.of("in.mkv"), out, RENDITION_720P), "-hls_segment_filename"))
                .isEqualTo("/out/vid/720p_%03d.ts");
        assertThat(valueOf(builder.build(Path.of("in.mkv"), out, low), "-hls_segment_filename"))
                .isEqualTo("/out/vid/240p_%03d.ts");
    }

    @Test
    void encodesWithTheCodecsTheMasterPlaylistAdvertises() {
        List<String> command = builder.build(Path.of("in.mkv"), Path.of("out"), RENDITION_720P);

        // These four must stay in step with HlsRendition.CODECS ("avc1.4d001f,mp4a.40.2").
        assertThat(valueOf(command, "-c:v")).isEqualTo("libx264");
        assertThat(valueOf(command, "-profile:v")).isEqualTo("main");
        assertThat(valueOf(command, "-level")).isEqualTo("3.1");
        assertThat(valueOf(command, "-c:a")).isEqualTo("aac");
    }

    private static String valueOf(List<String> command, String flag) {
        int index = command.indexOf(flag);
        assertThat(index).as("flag %s present", flag).isNotNegative();
        return command.get(index + 1);
    }
}
