package com.azt.streaming.transcoding.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.azt.streaming.support.PropertiesFixture;
import com.azt.streaming.transcoding.domain.AudioPlan;
import com.azt.streaming.transcoding.domain.AudioPreferences;
import com.azt.streaming.transcoding.domain.EncoderChoice;
import com.azt.streaming.transcoding.domain.HlsRendition;
import com.azt.streaming.transcoding.domain.PlannedRendition;
import com.azt.streaming.transcoding.domain.ProbedAudio;
import com.azt.streaming.transcoding.domain.SubtitlePlan;
import com.azt.streaming.transcoding.domain.TranscodePlan;
import com.azt.streaming.transcoding.domain.UndecodableAudioPolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FfmpegCommandBuilderTest {

    private static final HlsRendition RENDITION_1080P = new HlsRendition("1080p", 1920, 1080, 6000);
    private static final HlsRendition RENDITION_720P = new HlsRendition("720p", 1280, 720, 3000);
    private static final HlsRendition RENDITION_240P = new HlsRendition("240p", 426, 240, 500);

    private static final List<PlannedRendition> LADDER =
            List.of(PlannedRendition.encoded(RENDITION_720P), PlannedRendition.encoded(RENDITION_240P));

    private static final Path OUT = Path.of("/out/vid");

    private static final ProbedAudio STEREO_AAC = new ProbedAudio(0, "aac", "LC", 2, 48000, "eng", null, true);
    private static final ProbedAudio SURROUND_EAC3 = new ProbedAudio(0, "eac3", null, 6, 48000, "eng", null, true);

    private static final AudioPreferences AUDIO =
            new AudioPreferences(List.of("aac"), 0, 0, 64, 512, UndecodableAudioPolicy.PASSTHROUGH);

    private static final EncoderChoice LIBX264 = EncoderChoice.of("libx264");

    private final FfmpegCommandBuilder builder =
            new FfmpegCommandBuilder(PropertiesFixture.defaults().binary("ffmpeg").build());

    /** The 24 fps ladder at 4-second segments: 96 frames per GOP. */
    private static TranscodePlan plan(List<PlannedRendition> rungs, AudioPlan audio) {
        return new TranscodePlan(rungs, audio, List.of(), 96);
    }

    private List<String> command() {
        return builder.build(
                Path.of("/in/movie.mkv"), OUT, plan(LADDER, AudioPlan.encode(STEREO_AAC, "aac", AUDIO)), LIBX264);
    }

    @Test
    void putsTheConfiguredBinaryFirstAndTheOutputPlaylistLast() {
        assertThat(command().getFirst()).isEqualTo("ffmpeg");
        assertThat(command().getLast()).isEqualTo("/out/vid/%v.m3u8");
    }

    @Test
    void encodesTheWholeLadderInOneInvocation() {
        // One -i, so the source is decoded once and fed to every rung, rather than re-decoded.
        assertThat(command().stream().filter("-i"::equals).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("scales each rung from the one above it rather than from the source")
    void scalesInACascade() {
        // Four independent scalers each read full 1080p frames. Deriving each rung from the one
        // above measures 21% less CPU across the ladder, and the bottom rung — four resampling
        // steps from the source — still measures SSIM 0.998 against a direct downscale.
        assertThat(valueOf(command(), "-filter_complex"))
                .isEqualTo("[0:v]scale=w=1280:h=720,split=2[v0out][chain0];[chain0]scale=w=426:h=240[v1out]");
    }

    @Test
    void derivesMaxrateAndBufsizePerRung() {
        assertThat(valueOf(command(), "-b:v:0")).isEqualTo("3000k");
        assertThat(valueOf(command(), "-maxrate:v:0")).isEqualTo("3210k"); // 3000 * 1.07
        assertThat(valueOf(command(), "-bufsize:v:0")).isEqualTo("4500k"); // 3000 * 1.5
        assertThat(valueOf(command(), "-b:v:1")).isEqualTo("500k");
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
    }

    @Test
    @DisplayName("puts the audio in its own variant that every rung references")
    void sharesOneAudioRenditionAcrossTheLadder() {
        // Five rungs used to mean five AAC encodes of one track and five copies of it in the
        // segments. agroup: makes it a single variant; the master playlist joins them with AUDIO=.
        assertThat(valueOf(command(), "-var_stream_map"))
                .isEqualTo("v:0,agroup:aud,name:720p v:1,agroup:aud,name:240p a:0,agroup:aud,name:audio,default:yes");
        assertThat(command().stream().filter("-c:a:0"::equals).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("never emits an audio bitrate it does not have")
    void neverEmitsAZeroAudioBitrate() {
        // The regression this whole reshape exists for. A copied top rung declared an audio bitrate
        // of zero so the bandwidth arithmetic would not double-count the container's audio, and
        // that zero reached the command line as `-b:a:0 0k` on every source whose audio had to be
        // re-encoded beneath it. There is one audio stream now and it always has a real bitrate.
        for (List<String> command : List.of(
                command(),
                builder.build(Path.of("in.mkv"), OUT, copyPlan(AudioPlan.encode(SURROUND_EAC3, "aac", AUDIO)), LIBX264),
                builder.build(Path.of("in.mkv"), OUT, copyPlan(AudioPlan.copy(SURROUND_EAC3, null)), LIBX264))) {
            for (int i = 0; i < command.size() - 1; i++) {
                if (command.get(i).startsWith("-b:a")) {
                    assertThat(command.get(i + 1)).as("audio bitrate argument").doesNotStartWith("0");
                }
            }
        }
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
        // By time, not by frame count: the source is an arbitrary torrent and may be variable frame
        // rate, where a frame count is the wrong unit. Segments must start on a keyframe or the
        // player cannot switch rungs cleanly.
        assertThat(valueOf(command(), "-force_key_frames:v:0")).isEqualTo("expr:gte(t,n_forced*4)");
        assertThat(valueOf(command(), "-hls_time")).isEqualTo("4");
        assertThat(valueOf(command(), "-hls_flags")).isEqualTo("independent_segments");
        assertThat(valueOf(command(), "-hls_segment_type")).isEqualTo("fmp4");
    }

    @Test
    @DisplayName("caps the GOP when the frame rate is known, and does not guess when it is not")
    void capsTheGopOnlyWhenTheFrameRateWasReported() {
        assertThat(valueOf(command(), "-g:v:0")).isEqualTo("96");
        assertThat(valueOf(command(), "-keyint_min:v:0")).isEqualTo("96");

        // A source whose frame rate ffprobe would not report. A GOP derived from a guess would be
        // applied to every frame; -force_key_frames needs no frame rate and still holds the
        // segment boundary.
        List<String> unknownRate = builder.build(
                Path.of("in.mkv"),
                OUT,
                new TranscodePlan(LADDER, AudioPlan.encode(STEREO_AAC, "aac", AUDIO), List.of(), 0),
                LIBX264);
        assertThat(unknownRate).doesNotContain("-g:v:0", "-keyint_min:v:0");
        assertThat(valueOf(unknownRate, "-force_key_frames:v:0")).isEqualTo("expr:gte(t,n_forced*4)");
    }

    @Test
    void usesTheConfiguredSegmentDuration() {
        FfmpegCommandBuilder tenSecond = new FfmpegCommandBuilder(
                PropertiesFixture.defaults().segmentDuration(Duration.ofSeconds(10)).build());
        List<String> command = tenSecond.build(
                Path.of("in.mkv"), OUT, plan(LADDER, AudioPlan.encode(STEREO_AAC, "aac", AUDIO)), LIBX264);

        assertThat(valueOf(command, "-hls_time")).isEqualTo("10");
        // The keyframe interval has to follow the segment duration, or boundaries stop aligning.
        assertThat(valueOf(command, "-force_key_frames:v:0")).isEqualTo("expr:gte(t,n_forced*10)");
    }

    @Test
    void omitsAudioEntirelyWhenTheSourceHasNone() {
        // `-map a:0` against a file with no audio track fails the whole encode, and a torrent is not
        // a file we chose. The stream map must drop its audio references in step.
        List<String> silent =
                builder.build(Path.of("in.mkv"), OUT, plan(LADDER, AudioPlan.none("no audio")), LIBX264);

        assertThat(silent).doesNotContain("-c:a:0", "-b:a:0", "-ac:a:0", "-ar:a:0").contains("-an");
        assertThat(valueOf(silent, "-var_stream_map")).isEqualTo("v:0,name:720p v:1,name:240p");
    }

    @Test
    @DisplayName("copies an audio track that this build cannot decode, rather than failing")
    void copiesUndecodableAudio() {
        List<String> command =
                builder.build(Path.of("in.mkv"), OUT, plan(LADDER, AudioPlan.copy(SURROUND_EAC3, "no decoder")), LIBX264);

        assertThat(valueOf(command, "-c:a:0")).isEqualTo("copy");
        // Nothing that instructs an encoder or a resampler: a copied track has neither.
        assertThat(command).doesNotContain("-b:a:0", "-ac:a:0", "-ar:a:0");
    }

    @Test
    void usesTheResolvedEncoderAndItsPresetWhenItHasOne() {
        assertThat(valueOf(command(), "-c:v:0")).isEqualTo("libx264");
        assertThat(valueOf(command(), "-c:a:0")).isEqualTo("aac");
        // No preset resolved, so none is emitted — passing one to an encoder that has none puts a
        // warning in the log on every single encode.
        assertThat(command()).doesNotContain("-preset:v:0");

        List<String> presetted = builder.build(
                Path.of("in.mkv"),
                OUT,
                plan(LADDER, AudioPlan.encode(STEREO_AAC, "aac", AUDIO)),
                new EncoderChoice("libx264", "veryfast"));
        assertThat(valueOf(presetted, "-preset:v:0")).isEqualTo("veryfast");
    }

    @Test
    @DisplayName("never reads the terminal, and reports its progress somewhere readable")
    void runsUnattended() {
        // Without -nostdin an ffmpeg that decides to ask something blocks on the parent's stdin
        // until the deadline kills it, with nothing in the log to say why.
        assertThat(command()).contains("-nostdin", "-y", "-nostats");
        assertThat(valueOf(command(), "-progress")).isEqualTo("pipe:1");
    }

    @Test
    @DisplayName("maps a copied rung off the input, not out of the filter graph")
    void copiedRungBypassesTheFilterGraph() {
        // A copied stream is never decoded, so there is no frame for a scaler to receive. Routing
        // it through the graph is not a quality choice, it is a command ffmpeg refuses.
        List<String> command =
                builder.build(Path.of("/in/movie.mkv"), OUT, copyPlan(AudioPlan.copy(STEREO_AAC, null)), LIBX264);

        assertThat(valueOf(command, "-c:v:0")).isEqualTo("copy");
        assertThat(command).containsSequence("-map", "0:v:0");
        // The chain covers the encoded rungs only; a branch nothing consumes is an error.
        assertThat(valueOf(command, "-filter_complex"))
                .isEqualTo("[0:v]scale=w=1280:h=720,split=2[v0out][chain0];[chain0]scale=w=426:h=240[v1out]");
        assertThat(command).doesNotContain("-force_key_frames:v:0", "-b:v:0");
        assertThat(valueOf(command, "-force_key_frames:v:1")).isEqualTo("expr:gte(t,n_forced*4)");
    }

    @Test
    @DisplayName("the thread budget reaches each encoded rung, and the copied one gets none")
    void emitsThePerRungThreadBudget() {
        List<String> command = builder.build(
                Path.of("/in/movie.mkv"),
                OUT,
                new TranscodePlan(LADDER, AudioPlan.encode(STEREO_AAC, "aac", AUDIO), List.of(), 96, 6),
                LIBX264);

        assertThat(valueOf(command, "-threads:v:0")).isEqualTo("6");
        // Per stream rather than global: aimed globally it would also hit a copied rung, which is
        // a remux and has nothing to thread.
        assertThat(command).doesNotContain("-threads");
    }

    @Test
    @DisplayName("a small rung gets x264's height cap back, which an explicit -threads removes")
    void reappliesTheHeightCapExplicitThreadsBypasses() {
        // x264 caps its own thread count at half the picture's macroblock rows — but only on the
        // auto path. Passing a number silently drops that protection, so a 240p rung (15 rows of
        // macroblocks) asked for eight frame threads would get eight frames of latency and eight
        // frame buffers to divide fifteen rows between.
        List<String> command = builder.build(
                Path.of("/in/movie.mkv"),
                OUT,
                new TranscodePlan(LADDER, AudioPlan.encode(STEREO_AAC, "aac", AUDIO), List.of(), 96, 8),
                LIBX264);

        assertThat(valueOf(command, "-threads:v:0")).isEqualTo("8");
        assertThat(Integer.parseInt(valueOf(command, "-threads:v:1"))).isLessThan(8);
    }

    @Test
    @DisplayName("zero threads means ffmpeg decides, and no flag is emitted at all")
    void omitsTheFlagWhenSizingIsLeftToFfmpeg() {
        assertThat(command()).noneMatch(arg -> arg.startsWith("-threads"));
    }

    @Test
    @DisplayName("the serial cascade is filtered on one thread")
    void filtersOnOneThread() {
        // A property of the graph, not of the host: each rung scales from the one above it, so
        // slice-threading each scale across every core buys barriers rather than speed.
        assertThat(valueOf(command(), "-filter_complex_threads")).isEqualTo("1");
    }

    @Test
    @DisplayName("every encoded rung is pinned to 8-bit, whatever the source decoded to")
    void pinsThePixelFormat() {
        // Nothing set an output pixel format before, so it was whatever the decoder and swscale
        // negotiated. A 10-bit HEVC source gives yuv420p10le, libx264 then encodes High 10, and
        // that contradicts the "main" profile emitted beside it — a ladder that either fails or
        // advertises a CODECS string no browser will play.
        assertThat(valueOf(command(), "-pix_fmt:v:0")).isEqualTo("yuv420p");
        assertThat(valueOf(command(), "-pix_fmt:v:1")).isEqualTo("yuv420p");
    }

    @Test
    @DisplayName("scene-change keyframes are off, which -g never did")
    void suppressesSceneChangeKeyframes() {
        // -force_key_frames already guarantees a keyframe wherever a segment boundary needs one,
        // so a second one on every cut is bitrate spent for nothing at these rung sizes. -g and
        // -keyint_min cap the GOP; they have never suppressed scenecut, though the comment beside
        // them used to say they did.
        assertThat(valueOf(command(), "-sc_threshold:v:0")).isEqualTo("0");
    }

    @Test
    @DisplayName("omits the filter graph entirely when every rung is copied")
    void copyOnlyLadderHasNoFilterGraph() {
        // Reachable: an H.264 source shorter than the shortest configured rung needs one copied
        // rung and no encoder at all. An empty filter graph is a parse error.
        List<String> command = builder.build(
                Path.of("in.mkv"),
                OUT,
                plan(List.of(new PlannedRendition(RENDITION_1080P, true)), AudioPlan.copy(STEREO_AAC, null)),
                LIBX264);

        assertThat(command).doesNotContain("-filter_complex");
        assertThat(valueOf(command, "-var_stream_map"))
                .isEqualTo("v:0,agroup:aud,name:1080p a:0,agroup:aud,name:audio,default:yes");
    }

    @Test
    void buildsASubtitleCommandThatTouchesNothingElse() {
        SubtitlePlan subtitle = new SubtitlePlan(2, "sub_pt-5", "Portuguese (Brazilian)", "pt", false, false);
        List<String> command = builder.buildSubtitle(Path.of("/in/movie.mkv"), OUT.resolve("sub_pt-5.vtt"), subtitle);

        assertThat(command).containsSequence("-map", "0:s:2").containsSequence("-c:s", "webvtt");
        assertThat(command.getLast()).isEqualTo("/out/vid/sub_pt-5.vtt");
    }

    @Test
    void buildsAPosterCommandThatSeeksBeforeTheInput() {
        List<String> poster =
                builder.buildPoster(Path.of("/in/movie.mkv"), OUT.resolve("poster.jpg"), Duration.ofSeconds(5));

        // -ss before -i is input seeking: ffmpeg jumps, rather than decoding and discarding.
        assertThat(poster.indexOf("-ss")).isLessThan(poster.indexOf("-i"));
        assertThat(valueOf(poster, "-ss")).isEqualTo("5");
        assertThat(valueOf(poster, "-vf")).isEqualTo("thumbnail,scale=w=480:h=-2");
        assertThat(valueOf(poster, "-frames:v")).isEqualTo("1");
        assertThat(poster.getLast()).isEqualTo("/out/vid/poster.jpg");
    }

    @Test
    void omitsTheSeekEntirelyWhenThereIsNone() {
        // The retry for a source shorter than the offset: seeking past the end produces no frame at
        // all, so the fallback has to start from the beginning rather than seek to zero.
        assertThat(builder.buildPoster(Path.of("/in/short.mkv"), OUT.resolve("poster.jpg"), null))
                .doesNotContain("-ss");
    }

    private TranscodePlan copyPlan(AudioPlan audio) {
        return plan(
                List.of(
                        new PlannedRendition(RENDITION_1080P, true),
                        PlannedRendition.encoded(RENDITION_720P),
                        PlannedRendition.encoded(RENDITION_240P)),
                audio);
    }

    private static String valueOf(List<String> command, String flag) {
        int index = command.indexOf(flag);
        assertThat(index).as("flag %s present", flag).isNotNegative();
        return command.get(index + 1);
    }

    @Test
    @DisplayName("the audio arguments come from the source, not from a fixed configuration")
    void audioArgumentsFollowTheSource() {
        ProbedAudio surround = new ProbedAudio(0, "eac3", null, 6, 48000, "eng", null, true);
        List<String> command = builder.build(
                Path.of("in.mkv"), OUT, plan(LADDER, AudioPlan.encode(surround, "aac", AUDIO)), LIBX264);

        // 6 channels out, not 2, and 64 kbps a channel rather than a flat 128.
        assertThat(valueOf(command, "-ac:a:0")).isEqualTo("6");
        assertThat(valueOf(command, "-b:a:0")).isEqualTo("384k");

        ProbedAudio cd = new ProbedAudio(0, "mp3", null, 2, 44100, "eng", null, true);
        List<String> fromCd = builder.build(
                Path.of("in.mkv"), OUT, plan(LADDER, AudioPlan.encode(cd, "aac", AUDIO)), LIBX264);

        // 44.1 kHz stays 44.1: forcing 48 put every CD-rate source through a resampler for nothing.
        assertThat(valueOf(fromCd, "-ar:a:0")).isEqualTo("44100");
        assertThat(valueOf(fromCd, "-b:a:0")).isEqualTo("128k");
    }

    @Test
    @DisplayName("the audio-only command writes the audio rendition and touches nothing else")
    void buildsAnAudioOnlyCommand() {
        ProbedAudio surround = new ProbedAudio(0, "eac3", null, 6, 48000, "eng", null, true);
        List<String> command = builder.buildAudioOnly(
                Path.of("/in/movie.mkv"), OUT, AudioPlan.encode(surround, "aac", AUDIO));

        // Without -vn the muxer takes the video too and overwrites the rungs this exists to keep.
        assertThat(command).contains("-vn", "-sn", "-dn");
        assertThat(command).doesNotContain("-filter_complex", "-c:v:0");
        assertThat(valueOf(command, "-c:a:0")).isEqualTo("aac");
        assertThat(command.getLast()).isEqualTo("/out/vid/audio.m3u8");

        // Literal, not templated. ffmpeg substitutes %v in -hls_fmp4_init_filename only when
        // var_stream_map declares two or more variants; with one it writes a file called
        // "%v_init.mp4" and points the playlist at it.
        assertThat(valueOf(command, "-hls_fmp4_init_filename")).isEqualTo("audio_init.mp4");
        assertThat(valueOf(command, "-hls_segment_filename")).isEqualTo("/out/vid/audio_%03d.m4s");
    }
}
