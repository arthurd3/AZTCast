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
    private static final HlsRendition RENDITION_240P = new HlsRendition("240p", 426, 240, 500, 64);
    private static final List<HlsRendition> LADDER = List.of(RENDITION_720P, RENDITION_240P);

    private static final Path OUT = Path.of("/out/vid");

    private final FfmpegCommandBuilder builder =
            new FfmpegCommandBuilder(PropertiesFixture.defaults().binary("ffmpeg").build());

    private List<String> command() {
        return builder.build(Path.of("/in/movie.mkv"), OUT, LADDER, true);
    }

    @Test
    void putsTheConfiguredBinaryFirstAndTheOutputPlaylistLast() {
        assertThat(command().getFirst()).isEqualTo("ffmpeg");
        assertThat(command().getLast()).isEqualTo("/out/vid/%v.m3u8");
    }

    @Test
    void encodesTheWholeLadderInOneInvocation() {
        // One -i, so the source is decoded once and split, rather than re-decoded per rung.
        assertThat(command().stream().filter("-i"::equals).count()).isEqualTo(1);
        assertThat(valueOf(command(), "-filter_complex"))
                .isEqualTo("[0:v]split=2[v0][v1];[v0]scale=w=1280:h=720[v0out];[v1]scale=w=426:h=240[v1out]");
    }

    @Test
    void derivesMaxrateAndBufsizePerRung() {
        assertThat(valueOf(command(), "-b:v:0")).isEqualTo("3000k");
        assertThat(valueOf(command(), "-maxrate:v:0")).isEqualTo("3210k"); // 3000 * 1.07
        assertThat(valueOf(command(), "-bufsize:v:0")).isEqualTo("4500k"); // 3000 * 1.5
        assertThat(valueOf(command(), "-b:v:1")).isEqualTo("500k");
        assertThat(valueOf(command(), "-b:a:1")).isEqualTo("64k");
    }

    @Test
    void keepsEveryOutputFilenameFlat() {
        // The frozen playback contract is /api/v1/stream/{videoId}/{file} with {file} a SINGLE path
        // segment. ffmpeg's usual multi-rung recipe writes stream_0/playlist.m3u8, which that
        // mapping cannot match — the ladder would be unreachable. The name: key in var_stream_map
        // is what keeps %v expanding to a rung name instead of an index.
        assertThat(valueOf(command(), "-hls_segment_filename")).isEqualTo("/out/vid/%v_%03d.m4s");
        assertThat(valueOf(command(), "-hls_fmp4_init_filename")).isEqualTo("%v_init.mp4");
        assertThat(command().getLast()).doesNotContain("stream_");
        assertThat(valueOf(command(), "-var_stream_map")).isEqualTo("v:0,a:0,name:720p v:1,a:1,name:240p");
    }

    @Test
    void neverAsksFfmpegToWriteTheMasterPlaylist() {
        // -master_pl_name would make ffmpeg write master.m3u8 when the encode STARTS. Its presence
        // is this service's readiness sentinel, so that would announce a ladder whose variants do
        // not exist yet. MasterPlaylistWriter writes it, last.
        assertThat(command()).doesNotContain("-master_pl_name");
    }

    @Test
    void setsTheProfilePerVideoStream() {
        // Not optional and not global: without a per-stream -profile:v:N, libopenh264 silently drops
        // to Constrained Baseline, and the CODECS the master advertises becomes a lie.
        assertThat(valueOf(command(), "-profile:v:0")).isEqualTo("main");
        assertThat(valueOf(command(), "-profile:v:1")).isEqualTo("main");
    }

    @Test
    void forcesKeyframesOnSegmentBoundariesByTime() {
        // By time, not by -g frame count: the source is an arbitrary torrent and may be variable
        // frame rate, where a frame count is the wrong unit. Segments must start on a keyframe or
        // the player cannot switch rungs cleanly.
        assertThat(valueOf(command(), "-force_key_frames")).isEqualTo("expr:gte(t,n_forced*4)");
        assertThat(valueOf(command(), "-hls_time")).isEqualTo("4");
        assertThat(valueOf(command(), "-hls_flags")).isEqualTo("independent_segments");
        assertThat(valueOf(command(), "-hls_segment_type")).isEqualTo("fmp4");
    }

    @Test
    void usesTheConfiguredSegmentDuration() {
        FfmpegCommandBuilder tenSecond = new FfmpegCommandBuilder(
                PropertiesFixture.defaults().segmentDuration(Duration.ofSeconds(10)).build());
        List<String> command = tenSecond.build(Path.of("in.mkv"), OUT, LADDER, true);

        assertThat(valueOf(command, "-hls_time")).isEqualTo("10");
        // The keyframe interval has to follow the segment duration, or boundaries stop aligning.
        assertThat(valueOf(command, "-force_key_frames")).isEqualTo("expr:gte(t,n_forced*10)");
    }

    @Test
    void omitsAudioEntirelyWhenTheSourceHasNone() {
        // `-map a:0` against a file with no audio track fails the whole encode, and a torrent is not
        // a file we chose. The stream map must drop its audio references in step.
        List<String> silent = builder.build(Path.of("in.mkv"), OUT, LADDER, false);

        assertThat(silent).doesNotContain("-c:a:0", "-b:a:0", "-ac", "-ar");
        assertThat(valueOf(silent, "-var_stream_map")).isEqualTo("v:0,name:720p v:1,name:240p");
    }

    @Test
    void usesTheConfiguredEncoder() {
        assertThat(valueOf(command(), "-c:v:0")).isEqualTo("libx264");
        assertThat(valueOf(command(), "-c:a:0")).isEqualTo("aac");
    }

    private static String valueOf(List<String> command, String flag) {
        int index = command.indexOf(flag);
        assertThat(index).as("flag %s present", flag).isNotNegative();
        return command.get(index + 1);
    }
}
