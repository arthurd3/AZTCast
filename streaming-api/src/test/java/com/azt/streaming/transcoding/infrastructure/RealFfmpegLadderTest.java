package com.azt.streaming.transcoding.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.azt.streaming.shared.config.StreamingProperties;
import com.azt.streaming.shared.storage.MediaStorage;
import com.azt.streaming.support.PropertiesFixture;
import com.azt.streaming.transcoding.domain.FfmpegSupport;
import com.azt.streaming.transcoding.domain.HlsRendition;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the real ffmpeg and ffprobe over a generated clip.
 *
 * <p>Every other test in this package asserts the <em>shape</em> of a command. This one is the only
 * thing that proves the command actually works, and it exists because the defects it guards are all
 * invisible to a command-shape assertion:
 *
 * <ul>
 *   <li>The multi-rung ffmpeg recipe writes {@code stream_0/playlist.m3u8}. The frozen playback
 *       mapping takes a single path segment, so a subdirectory makes the entire ladder unreachable
 *       — with no error anywhere in the encode.
 *   <li>The master playlist advertised one hardcoded {@code avc1.4d001f} for every rung. It is only
 *       correct at 720p; the level follows resolution, so 240p really is 2.1.
 *   <li>The top rung is copied rather than encoded when the source is already H.264. Whether that
 *       copy is real is not something a command-shape assertion can answer — {@code -c:v copy}
 *       wired through the filter graph fails, and wired correctly against a source that was quietly
 *       re-encoded upstream succeeds while losing a generation of quality.
 *   <li>An audio track this build cannot decode is copied into the segments instead of failing the
 *       ingestion. That path is reachable only from a real muxer, and it is the one that shipped.
 * </ul>
 *
 * <p>Skipped when ffmpeg is unavailable, so {@code mvn verify} stays green on a machine without it.
 */
class RealFfmpegLadderTest {

    private static final List<HlsRendition> LADDER =
            List.of(new HlsRendition("720p", 1280, 720, 3000), new HlsRendition("240p", 426, 240, 500));

    private static final Duration SEGMENT_DURATION = Duration.ofSeconds(4);

    private static String encoder;

    @BeforeAll
    static void requireFfmpegWithAnH264Encoder() {
        assumeTrue(onPath("ffmpeg") && onPath("ffprobe"), "ffmpeg/ffprobe not installed");
        encoder = availableEncoder().orElse(null);
        assumeTrue(encoder != null, "no H.264 encoder in this ffmpeg build");
    }

    @Test
    @DisplayName("produces a flat, keyframe-aligned ladder whose CODECS match what ffprobe sees")
    void producesALadderThatMatchesWhatItAdvertises(@TempDir Path tmp) throws Exception {
        Path source = tmp.resolve("source.mkv");
        generateClip(source, "aac", true);
        Path outputDirectory = Files.createDirectory(tmp.resolve("hls"));

        transcoder(realProperties(), outputDirectory, null)
                .transcodeToHls(source, "vid", percent -> {})
                .join();

        // 1. Flat: the frozen mapping /{videoId}/{file} cannot reach a subdirectory.
        assertThat(Files.walk(outputDirectory).filter(Files::isDirectory).toList())
                .as("no subdirectories — ffmpeg's default stream_%%v/ layout would be unreachable")
                .containsExactly(outputDirectory);

        // 2. Every file playback will be asked for actually exists, under the expected name.
        for (HlsRendition rendition : LADDER) {
            assertThat(outputDirectory.resolve(rendition.playlistFileName())).exists();
            assertThat(outputDirectory.resolve(rendition.initFileName())).exists();
            assertThat(outputDirectory.resolve(rendition.name() + "_000.m4s")).exists();
        }
        assertThat(outputDirectory.resolve(MediaStorage.MASTER_PLAYLIST)).exists();

        // 3. Exactly one audio rendition for the whole ladder, not one muxed into every rung.
        assertThat(outputDirectory.resolve("audio.m3u8")).exists();
        assertThat(outputDirectory.resolve("audio_init.mp4")).exists();
        String master = Files.readString(outputDirectory.resolve(MediaStorage.MASTER_PLAYLIST));
        assertThat(master.lines().filter(line -> line.startsWith("#EXT-X-MEDIA:TYPE=AUDIO"))).hasSize(1);
        assertThat(master.lines().filter(line -> line.contains("AUDIO=\"aud\""))).hasSize(LADDER.size());
        // The video variants carry no audio of their own any more.
        for (HlsRendition rendition : LADDER) {
            assertThat(audioStreamsIn(outputDirectory.resolve(rendition.playlistFileName())))
                    .as("%s must be video-only; its audio lives in the shared group", rendition.name())
                    .isZero();
        }

        // 4. The advertised CODECS equals what ffprobe independently reports for that rung, joined
        //    to the codec of the audio group the variant was pointed at.
        for (HlsRendition rendition : LADDER) {
            String truth = groundTruthCodecs(outputDirectory.resolve(rendition.playlistFileName()));
            assertThat(master)
                    .as("%s must advertise the codec string ffprobe reports for it", rendition.name())
                    .contains("CODECS=\"" + truth + ",mp4a.40.2\"");
        }

        // 5. Distinct rungs really do get distinct strings — the bug was one constant for all.
        assertThat(Pattern.compile("CODECS=\"([^\"]+)\"").matcher(master).results().count()).isEqualTo(2);

        // 6. No audio bitrate argument was ever zero. `-b:a:0 0k` was a real command this pipeline
        //    emitted whenever a copied rung sat above audio that had to be re-encoded.
        assertThat(master).doesNotContain("BANDWIDTH=0");

        // 7. Subtitles: the source's text track came out as WebVTT with a playlist beside it.
        assertThat(outputDirectory.resolve("sub_en-0.vtt")).exists();
        assertThat(outputDirectory.resolve("sub_en-0.m3u8")).exists();
        assertThat(Files.readString(outputDirectory.resolve("sub_en-0.vtt"))).startsWith("WEBVTT");
        assertThat(master).contains("#EXT-X-MEDIA:TYPE=SUBTITLES").contains("SUBTITLES=\"subs\"");

        // 8. Keyframe alignment: every segment but the last is one segment-duration long, which is
        //    what lets a player switch rungs without a gap.
        for (HlsRendition rendition : LADDER) {
            List<Double> durations = segmentDurations(outputDirectory.resolve(rendition.playlistFileName()));
            assertThat(durations).isNotEmpty();
            assertThat(durations.subList(0, durations.size() - 1))
                    .as("%s segments must land on segment boundaries", rendition.name())
                    .allMatch(d -> Math.abs(d - SEGMENT_DURATION.toSeconds()) < 0.2);
        }
    }

    @Test
    @DisplayName("copies the top rung's bitstream instead of re-encoding it")
    void copiesRatherThanReEncodesTheTopRung(@TempDir Path tmp) throws Exception {
        // 720p in, so the planner's copy rung and the configured 720p rung land on the same name.
        Path source = tmp.resolve("source.mkv");
        generateClip(source, "aac", false);
        Path outputDirectory = Files.createDirectory(tmp.resolve("hls"));

        transcoder(realProperties(), outputDirectory, null)
                .transcodeToHls(source, "vid", percent -> {})
                .join();

        // The whole claim, in one assertion: the elementary stream that comes out of the top rung
        // is byte-for-byte the one that went in. Anything that decoded and re-encoded — however
        // high the bitrate — would differ here.
        assertThat(videoBitstreamDigest(outputDirectory.resolve("720p.m3u8")))
                .as("the top rung must be the source's own bitstream, not a re-encode of it")
                .isEqualTo(videoBitstreamDigest(source));

        // And the rung below really was encoded, so this is a mixed ladder and not a copy of
        // everything: 240p cannot be a copy of a 720p source.
        assertThat(videoBitstreamDigest(outputDirectory.resolve("240p.m3u8")))
                .isNotEqualTo(videoBitstreamDigest(source));
    }

    @Test
    @DisplayName("publishes a video whose audio this build cannot decode, instead of failing it")
    void copiesAudioThisBuildCannotDecode(@TempDir Path tmp) throws Exception {
        assumeTrue(hasEncoder("ac3"), "no AC-3 encoder to build the fixture with");

        // The defect, reproduced with a codec this machine happens to have both halves of. The
        // shipped failure was E-AC-3 on a patent-free build: `Decoding requested, but no decoder
        // found for: eac3`, after a 756 MB download, as the tail of a forty-line stack trace.
        // Pretending AC-3 is undecodable puts the pipeline in exactly that position.
        Path source = tmp.resolve("source.mkv");
        generateClip(source, "ac3", false);
        Path outputDirectory = Files.createDirectory(tmp.resolve("hls"));

        transcoder(realProperties(), outputDirectory, "ac3")
                .transcodeToHls(source, "vid", percent -> {})
                .join();

        // It produced a ladder rather than an exception, and the audio came through untouched.
        assertThat(outputDirectory.resolve(MediaStorage.MASTER_PLAYLIST)).exists();
        assertThat(outputDirectory.resolve("audio.m3u8")).exists();
        assertThat(audioCodecIn(outputDirectory.resolve("audio.m3u8"))).isEqualTo("ac3");

        // And it says so. A player that cannot handle AC-3 learns it from the manifest rather than
        // from a decoder error on the first segment.
        assertThat(Files.readString(outputDirectory.resolve(MediaStorage.MASTER_PLAYLIST)))
                .contains(",ac-3\"")
                .doesNotContain("mp4a.40.2");
    }

    @Test
    @DisplayName("keeps a 5.1 source at 5.1 rather than folding it to stereo")
    void preservesTheSourceChannelLayout(@TempDir Path tmp) throws Exception {
        assumeTrue(hasEncoder("ac3"), "no AC-3 encoder to build the fixture with");

        // AC-3 because this build can both encode it (to make the fixture) and decode it (to prove
        // the pipeline re-encodes rather than passes through). The shipped case is E-AC-3, which
        // behaves identically on a host that has its decoder.
        Path source = tmp.resolve("source.mkv");
        generateSurroundClip(source);
        assertThat(audioChannelsIn(source)).isEqualTo(6);
        Path outputDirectory = Files.createDirectory(tmp.resolve("hls"));

        transcoder(realProperties(), outputDirectory, null)
                .transcodeToHls(source, "vid", percent -> {})
                .join();

        // Six channels out, not two. Folding to stereo threw away four channels for a downmix the
        // viewer's own output device does better.
        assertThat(audioCodecIn(outputDirectory.resolve("audio.m3u8"))).isEqualTo("aac");
        assertThat(audioChannelsIn(outputDirectory.resolve("audio.m3u8"))).isEqualTo(6);

        String master = Files.readString(outputDirectory.resolve(MediaStorage.MASTER_PLAYLIST));
        assertThat(master).contains("CHANNELS=\"6\"").contains("mp4a.40.2");
        // AAC is mp4a.40.2 whatever its layout, so there is no video-only family to advertise.
        assertThat(master.lines().filter(line -> line.startsWith("#EXT-X-STREAM-INF"))).hasSize(LADDER.size());
    }

    @Test
    @DisplayName("leaves nothing behind when the encode fails")
    void discardsAPartialLadder(@TempDir Path tmp) throws Exception {
        Path source = tmp.resolve("not-a-video.mkv");
        Files.writeString(source, "this is not a media file");
        Path hlsRoot = Files.createDirectory(tmp.resolve("hls"));

        StreamingProperties properties = PropertiesFixture.defaults()
                .hlsDir(hlsRoot)
                .binary("ffmpeg")
                .probeBinary("ffprobe")
                .videoCodec(encoder)
                .ffmpegTimeout(Duration.ofMinutes(2))
                .segmentDuration(SEGMENT_DURATION)
                .renditions(renditionConfig())
                .build();
        ProcessRunner processRunner = new ProcessRunner();
        // The real storage, not the stub: the discard path is the thing under test, and it lives
        // there. Both roots are created on demand by the accessors.
        var storage = new com.azt.streaming.shared.storage.FileSystemMediaStorage(properties);
        FfmpegCommandBuilder commandBuilder = new FfmpegCommandBuilder(properties);
        FfmpegMediaTranscoder transcoder = new FfmpegMediaTranscoder(
                storage,
                new FfprobeMediaProbe(properties, processRunner),
                new TranscodePlanner(properties, new FfmpegCapabilities(properties, processRunner)),
                commandBuilder,
                processRunner,
                new MasterPlaylistWriter(),
                new LadderIntegrity(),
                new SubtitlePublisher(commandBuilder, processRunner),
                new VariantWeigher(),
                new SimpleMeterRegistry(),
                properties);

        assertThatThrownBy(() -> transcoder.transcodeToHls(source, "doomed", percent -> {}).join())
                .isNotNull();

        // A failed encode used to leave partial segments and half-written playlists on disk until
        // the reaper came for them a week later, invisible to the library the whole time.
        assertThat(hlsRoot.resolve("doomed")).doesNotExist();
    }

    /**
     * The transcoder wired by hand against real binaries.
     *
     * @param undecodable a codec to pretend this build has no decoder for, or null for the truth
     */
    private static FfmpegMediaTranscoder transcoder(
            StreamingProperties properties, Path outputDirectory, String undecodable) {
        ProcessRunner processRunner = new ProcessRunner();
        FfmpegCommandBuilder commandBuilder = new FfmpegCommandBuilder(properties);
        FfmpegCapabilities capabilities = undecodable == null
                ? new FfmpegCapabilities(properties, processRunner)
                : withoutDecoder(properties, processRunner, undecodable);
        return new FfmpegMediaTranscoder(
                fixedStorage(outputDirectory),
                new FfprobeMediaProbe(properties, processRunner),
                new TranscodePlanner(properties, capabilities),
                commandBuilder,
                processRunner,
                new MasterPlaylistWriter(),
                new LadderIntegrity(),
                new SubtitlePublisher(commandBuilder, processRunner),
                new VariantWeigher(),
                new SimpleMeterRegistry(),
                properties);
    }

    /** The real capability listing with one decoder removed, so the fallback path is reachable. */
    private static FfmpegCapabilities withoutDecoder(
            StreamingProperties properties, ProcessRunner processRunner, String codec) {
        return new FfmpegCapabilities(properties, processRunner) {
            @Override
            public FfmpegSupport support() {
                FfmpegSupport real = super.support();
                Set<String> decoders = new HashSet<>(real.decoders());
                decoders.remove(codec);
                return new FfmpegSupport(real.encoders(), decoders, real.hwaccels(), true);
            }
        };
    }

    /**
     * MD5 of the raw H.264 elementary stream, with the container stripped off.
     *
     * <p>Comparing files would prove nothing: the source is a Matroska and the rung is a chain of
     * fMP4 segments, so their bytes differ however faithful the copy. Remuxing both to Annex B
     * leaves only the encoded video, which is exactly what a copy must preserve and a re-encode
     * cannot.
     */
    private static String videoBitstreamDigest(Path media) throws Exception {
        Process process = new ProcessBuilder(List.of(
                        "ffmpeg", "-hide_banner", "-loglevel", "error",
                        "-i", media.toString(),
                        "-map", "0:v:0", "-c:v", "copy", "-f", "h264", "-"))
                .start();
        byte[] bitstream = process.getInputStream().readAllBytes();
        process.getErrorStream().readAllBytes();
        process.waitFor();
        assertThat(bitstream).as("no video bitstream read from %s", media).isNotEmpty();
        return java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("MD5").digest(bitstream));
    }

    /** Asks ffprobe directly, independently of the production parsing code. */
    private static String groundTruthCodecs(Path variantPlaylist) throws Exception {
        String csv = capture(List.of(
                "ffprobe", "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=profile,level", "-of", "csv=p=0", variantPlaylist.toString()));
        // ffprobe lists a playlist's streams twice — once under "programs", once at top level —
        // so csv output has a duplicate line. (The production parser reads the JSON top-level array
        // for the same reason.)
        String[] parts = csv.lines().filter(line -> !line.isBlank()).findFirst().orElseThrow().split(",");
        String profileHex = switch (parts[0]) {
            case "Main" -> "4d00";
            case "High" -> "6400";
            case "Constrained Baseline" -> "42e0";
            case "Baseline" -> "4200";
            default -> throw new IllegalStateException("unmapped profile " + parts[0]);
        };
        return "avc1.%s%02x".formatted(profileHex, Integer.parseInt(parts[1]));
    }

    private static int audioStreamsIn(Path media) throws Exception {
        String csv = capture(List.of(
                "ffprobe", "-v", "error", "-select_streams", "a",
                "-show_entries", "stream=index", "-of", "csv=p=0", media.toString()));
        return (int) csv.lines().filter(line -> !line.isBlank()).distinct().count();
    }

    private static String audioCodecIn(Path media) throws Exception {
        String csv = capture(List.of(
                "ffprobe", "-v", "error", "-select_streams", "a:0",
                "-show_entries", "stream=codec_name", "-of", "csv=p=0", media.toString()));
        // csv=p=0 leaves a trailing separator even on a single-field row.
        return csv.lines().filter(line -> !line.isBlank()).findFirst().orElseThrow().strip().replace(",", "");
    }

    private static List<Double> segmentDurations(Path playlist) throws IOException {
        return Files.readAllLines(playlist).stream()
                .filter(line -> line.startsWith("#EXTINF:"))
                .map(line -> Double.parseDouble(line.substring(8, line.indexOf(','))))
                .toList();
    }

    /**
     * A 720p clip with the requested audio codec, and optionally a subtitle track.
     *
     * <p>Matroska rather than MP4 so a subtitle stream can ride along: MP4 carries {@code mov_text}
     * only, and muxing SRT into one fails.
     */
    private static void generateClip(Path target, String audioCodec, boolean withSubtitles) throws Exception {
        Path subtitles = target.resolveSibling("fixture.srt");
        if (withSubtitles) {
            Files.writeString(
                    subtitles,
                    "1\n00:00:01,000 --> 00:00:04,000\nA caption that has to survive the pipeline.\n\n",
                    StandardCharsets.UTF_8);
        }
        List<String> command = new java.util.ArrayList<>(List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=25:duration=10",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=10"));
        if (withSubtitles) {
            command.addAll(List.of("-i", subtitles.toString()));
        }
        command.addAll(List.of("-c:v", encoder, "-c:a", audioCodec));
        if (withSubtitles) {
            command.addAll(List.of("-c:s", "srt", "-metadata:s:s:0", "language=eng"));
        }
        command.addAll(List.of("-shortest", "-y", target.toString()));
        capture(command);
        assertThat(target).as("fixture clip was not produced").exists();
    }

    /** A 720p clip with a real 5.1 AC-3 track, from one sine wave fanned across six channels. */
    private static void generateSurroundClip(Path target) throws Exception {
        capture(List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=25:duration=10",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=10",
                "-af", "pan=5.1|c0=c0|c1=c0|c2=c0|c3=c0|c4=c0|c5=c0",
                "-c:v", encoder, "-c:a", "ac3", "-b:a", "448k",
                "-shortest", "-y", target.toString()));
        assertThat(target).as("surround fixture clip was not produced").exists();
    }

    private static int audioChannelsIn(Path media) throws Exception {
        String csv = capture(List.of(
                "ffprobe", "-v", "error", "-select_streams", "a:0",
                "-show_entries", "stream=channels", "-of", "csv=p=0", media.toString()));
        return Integer.parseInt(
                csv.lines().filter(line -> !line.isBlank()).findFirst().orElseThrow().strip().replace(",", ""));
    }

    private static Optional<String> availableEncoder() {
        return List.of("libx264", "libopenh264").stream()
                .filter(RealFfmpegLadderTest::hasEncoder)
                .findFirst();
    }

    private static boolean hasEncoder(String name) {
        try {
            return capture(List.of("ffmpeg", "-hide_banner", "-encoders")).contains(name);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean onPath(String binary) {
        try {
            return new ProcessBuilder(binary, "-version")
                            .redirectErrorStream(true)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .start()
                            .waitFor()
                    == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static String capture(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.getErrorStream().readAllBytes();
        process.waitFor();
        return out;
    }

    /** Storage stub: the transcoder only asks it where to write, and to clean up after a failure. */
    private static MediaStorage fixedStorage(Path hlsDirectory) {
        return new MediaStorage() {
            @Override
            public Path downloadDirectoryFor(String videoId) {
                return hlsDirectory;
            }

            @Override
            public Path hlsDirectoryFor(String videoId) {
                return hlsDirectory;
            }

            @Override
            public boolean discardIncompleteHls(String videoId) {
                return false;
            }

            @Override
            public Optional<Path> existingDownload(String videoId) {
                return Optional.empty();
            }

            @Override
            public Optional<Path> resolveHlsAsset(String videoId, String fileName) {
                return Optional.of(hlsDirectory.resolve(fileName));
            }

            @Override
            public List<Path> listReadyVideoDirectories() {
                return List.of();
            }
        };
    }

    /**
     * Real binaries and the real encoder this build actually has. The shared fixture stubs both to
     * {@code /bin/true}, which is right for every other test and useless for this one.
     */
    private static StreamingProperties realProperties() {
        return PropertiesFixture.defaults()
                .binary("ffmpeg")
                .probeBinary("ffprobe")
                .videoCodec(encoder)
                .ffmpegTimeout(Duration.ofMinutes(5))
                .segmentDuration(SEGMENT_DURATION)
                .renditions(renditionConfig())
                .build();
    }

    private static List<StreamingProperties.Rendition> renditionConfig() {
        return LADDER.stream()
                .map(r -> new StreamingProperties.Rendition(r.name(), r.width(), r.height(), r.videoBitrateKbps()))
                .toList();
    }
}
