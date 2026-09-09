package com.azt.streaming.transcoding.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.azt.streaming.shared.config.StreamingProperties;
import com.azt.streaming.shared.storage.MediaStorage;
import com.azt.streaming.support.PropertiesFixture;
import com.azt.streaming.transcoding.domain.HlsRendition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the real ffmpeg and ffprobe over a generated clip.
 *
 * <p>Every other test in this package asserts the <em>shape</em> of a command. This one is the only
 * thing that proves the command actually works, and it exists because the two defects it guards are
 * both invisible to a command-shape assertion:
 *
 * <ul>
 *   <li>The multi-rung ffmpeg recipe writes {@code stream_0/playlist.m3u8}. The frozen playback
 *       mapping takes a single path segment, so a subdirectory makes the entire ladder unreachable
 *       — with no error anywhere in the encode.
 *   <li>The master playlist advertised one hardcoded {@code avc1.4d001f} for every rung. It is only
 *       correct at 720p; the level follows resolution, so 240p really is 2.1.
 * </ul>
 *
 * <p>Skipped when ffmpeg is unavailable, so {@code mvn verify} stays green on a machine without it.
 */
class RealFfmpegLadderTest {

    private static final List<HlsRendition> LADDER =
            List.of(new HlsRendition("720p", 1280, 720, 3000, 128), new HlsRendition("240p", 426, 240, 500, 64));

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
        Path source = tmp.resolve("source.mp4");
        generateClip(source);
        Path outputDirectory = Files.createDirectory(tmp.resolve("hls"));

        StreamingProperties properties = realProperties();
        ProcessRunner processRunner = new ProcessRunner();
        FfmpegMediaTranscoder transcoder = new FfmpegMediaTranscoder(
                fixedStorage(outputDirectory),
                new FfprobeMediaProbe(properties, processRunner),
                new FfmpegCommandBuilder(properties),
                processRunner,
                new MasterPlaylistWriter(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                properties);

        transcoder.transcodeToHls(source, "vid").join();

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

        // 3. The advertised CODECS equals what ffprobe independently reports for that rung.
        String master = Files.readString(outputDirectory.resolve(MediaStorage.MASTER_PLAYLIST));
        for (HlsRendition rendition : LADDER) {
            String truth = groundTruthCodecs(outputDirectory.resolve(rendition.playlistFileName()));
            assertThat(master)
                    .as("%s must advertise the codec string ffprobe reports for it", rendition.name())
                    .contains("CODECS=\"" + truth + "\"");
        }

        // 4. Distinct rungs really do get distinct strings — the bug was one constant for all.
        assertThat(Pattern.compile("CODECS=\"([^\"]+)\"").matcher(master).results().count()).isEqualTo(2);

        // 5. Keyframe alignment: every segment but the last is exactly one segment-duration long,
        //    which is what lets a player switch rungs without a gap.
        for (HlsRendition rendition : LADDER) {
            List<Double> durations = Files.readAllLines(outputDirectory.resolve(rendition.playlistFileName())).stream()
                    .filter(line -> line.startsWith("#EXTINF:"))
                    .map(line -> Double.parseDouble(line.substring(8, line.indexOf(','))))
                    .toList();
            assertThat(durations).isNotEmpty();
            assertThat(durations.subList(0, durations.size() - 1))
                    .as("%s segments must land on segment boundaries", rendition.name())
                    .allMatch(d -> Math.abs(d - SEGMENT_DURATION.toSeconds()) < 0.2);
        }
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
        return "avc1.%s%02x,mp4a.40.2".formatted(profileHex, Integer.parseInt(parts[1]));
    }

    private static void generateClip(Path target) throws Exception {
        capture(List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=25:duration=10",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=10",
                "-c:v", encoder, "-c:a", "aac", "-shortest", "-y", target.toString()));
    }

    private static Optional<String> availableEncoder() {
        try {
            String encoders = capture(List.of("ffmpeg", "-hide_banner", "-encoders"));
            return List.of("libx264", "libopenh264").stream().filter(encoders::contains).findFirst();
        } catch (Exception e) {
            return Optional.empty();
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
        String out = new String(process.getInputStream().readAllBytes());
        process.getErrorStream().readAllBytes();
        process.waitFor();
        return out;
    }

    /** Storage stub: the transcoder only asks it where to write. */
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
        StreamingProperties base = PropertiesFixture.defaults().build();
        return new StreamingProperties(
                base.storage(),
                new StreamingProperties.Ffmpeg(
                        "ffmpeg",
                        "ffprobe",
                        encoder,
                        Duration.ofMinutes(5),
                        SEGMENT_DURATION,
                        LADDER.stream()
                                .map(r -> new StreamingProperties.Rendition(
                                        r.name(), r.width(), r.height(), r.videoBitrateKbps(), r.audioBitrateKbps()))
                                .toList()),
                base.torrent(),
                base.transcoding(),
                base.playback(),
                base.redis(),
                base.web());
    }
}
