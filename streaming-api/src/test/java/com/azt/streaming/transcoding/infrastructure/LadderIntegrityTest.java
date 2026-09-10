package com.azt.streaming.transcoding.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.azt.streaming.transcoding.domain.AudioPlan;
import com.azt.streaming.transcoding.domain.AudioPreferences;
import com.azt.streaming.transcoding.domain.EncodedAudio;
import com.azt.streaming.transcoding.domain.EncodedRendition;
import com.azt.streaming.transcoding.domain.HlsRendition;
import com.azt.streaming.transcoding.domain.LadderReport;
import com.azt.streaming.transcoding.domain.ProbedAudio;
import com.azt.streaming.transcoding.domain.SubtitlePlan;
import com.azt.streaming.transcoding.domain.UndecodableAudioPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LadderIntegrityTest {

    private static final EncodedRendition RUNG =
            new EncodedRendition(new HlsRendition("720p", 1280, 720, 3000), "avc1.4d001f", 2_900_000, 3_210_000);

    private static final AudioPreferences PREFERENCES =
            new AudioPreferences(List.of("aac"), 0, 0, 64, 512, UndecodableAudioPolicy.PASSTHROUGH);

    private static final EncodedAudio AAC = new EncodedAudio(
            AudioPlan.encode(new ProbedAudio(0, "aac", "LC", 2, 48000, "eng", null, true), "aac", PREFERENCES),
            128_000,
            130_000);

    private final LadderIntegrity integrity = new LadderIntegrity();
    private final MasterPlaylistWriter writer = new MasterPlaylistWriter();

    /** A sound ladder: one rung, one audio rendition, everything on disk with bytes in it. */
    private void writeHealthyLadder(Path dir) throws IOException {
        for (String name : List.of("720p", "audio")) {
            Files.write(dir.resolve(name + "_init.mp4"), new byte[64]);
            Files.write(dir.resolve(name + "_000.m4s"), new byte[4096]);
            Files.writeString(
                    dir.resolve(name + ".m3u8"),
                    """
                    #EXTM3U
                    #EXT-X-VERSION:7
                    #EXT-X-MAP:URI="%s_init.mp4"
                    #EXTINF:4.000000,
                    %s_000.m4s
                    #EXT-X-ENDLIST
                    """
                            .formatted(name, name));
        }
    }

    private List<String> verify(Path dir) {
        return integrity.verify(dir, writer.render(List.of(RUNG), AAC, List.of()), List.of(RUNG), AAC, List.of());
    }

    @Test
    void passesASoundLadderWithoutComplaint(@TempDir Path dir) throws IOException {
        writeHealthyLadder(dir);

        assertThat(verify(dir)).isEmpty();
    }

    @Test
    @DisplayName("catches a segment that is not there")
    void catchesAMissingSegment(@TempDir Path dir) throws IOException {
        writeHealthyLadder(dir);
        Files.delete(dir.resolve("720p_000.m4s"));

        assertThat(verify(dir)).anyMatch(p -> p.contains("720p.m3u8") && p.contains("720p_000.m4s"));
    }

    @Test
    @DisplayName("counts a zero-byte segment as missing, because a player does")
    void catchesAnEmptySegment(@TempDir Path dir) throws IOException {
        writeHealthyLadder(dir);
        // What ffmpeg leaves when it is killed between creating a file and writing to it. A player
        // treats one as a decode error rather than as a gap it can skip.
        Files.write(dir.resolve("720p_000.m4s"), new byte[0]);

        assertThat(verify(dir)).anyMatch(p -> p.contains("missing or empty"));
    }

    @Test
    @DisplayName("catches a missing init segment, which the old walker never even looked at")
    void catchesAMissingInitSegment(@TempDir Path dir) throws IOException {
        writeHealthyLadder(dir);
        Files.delete(dir.resolve("audio_init.mp4"));

        assertThat(verify(dir)).anyMatch(p -> p.contains("audio.m3u8") && p.contains("audio_init.mp4"));
    }

    @Test
    void catchesAPlaylistFfmpegNeverFinished(@TempDir Path dir) throws IOException {
        writeHealthyLadder(dir);
        String truncated = Files.readString(dir.resolve("720p.m3u8")).replace("#EXT-X-ENDLIST\n", "");
        Files.writeString(dir.resolve("720p.m3u8"), truncated);

        assertThat(verify(dir)).anyMatch(p -> p.contains("#EXT-X-ENDLIST"));
    }

    @Test
    void catchesAPlaylistThatIsNotThereAtAll(@TempDir Path dir) throws IOException {
        writeHealthyLadder(dir);
        Files.delete(dir.resolve("audio.m3u8"));

        assertThat(verify(dir)).anyMatch(p -> p.contains("audio.m3u8") && p.contains("missing or unreadable"));
    }

    @Test
    void catchesASubtitleTrackThatWasAdvertisedButNotWritten(@TempDir Path dir) throws IOException {
        writeHealthyLadder(dir);
        SubtitlePlan subtitle = new SubtitlePlan(0, "sub_en-0", "English", "en", false, false);
        Files.writeString(dir.resolve("sub_en-0.m3u8"), "#EXTM3U\n");

        List<String> problems = integrity.verify(
                dir,
                writer.render(List.of(RUNG), AAC, List.of(subtitle)),
                List.of(RUNG),
                AAC,
                List.of(subtitle));

        assertThat(problems).anyMatch(p -> p.contains("sub_en-0.vtt"));
    }

    @Test
    @DisplayName("refuses a ladder no mainstream browser can assemble")
    void refusesAnUnplayableLadder(@TempDir Path dir) throws IOException {
        // The defect this class was written for. Five sound H.264 rungs, and every variant's CODECS
        // also names the E-AC-3 audio beside it, so Chrome rejects all five and plays nothing.
        writeHealthyLadder(dir);
        EncodedAudio eac3 = new EncodedAudio(
                AudioPlan.copy(new ProbedAudio(0, "eac3", null, 6, 48000, "eng", null, true), "no decoder"),
                640_000,
                700_000);
        String unplayable = """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-STREAM-INF:BANDWIDTH=3910000,RESOLUTION=1280x720,CODECS="avc1.4d001f,ec-3",AUDIO="aud"
                720p.m3u8
                """;

        assertThat(integrity.verify(dir, unplayable, List.of(RUNG), eac3, List.of()))
                .anyMatch(p -> p.contains("no variant is playable"));

        // And the shape the writer actually produces for that audio does pass, because it offers
        // the same rung a second time without the group.
        assertThat(integrity.verify(dir, writer.render(List.of(RUNG), eac3, List.of()), List.of(RUNG), eac3, List.of()))
                .isEmpty();
    }

    @Test
    @DisplayName("does not demand an init segment of a subtitle rendition, which never has one")
    void toleratesAWebvttRenditionWithNoMap(@TempDir Path dir) throws IOException {
        // fMP4 splits the header out of the media; WebVTT does not. Demanding an EXT-X-MAP of a
        // subtitle playlist reported a fault in every healthy ladder this service writes — and the
        // first real repair found four of them.
        writeHealthyLadder(dir);
        SubtitlePlan subtitle = new SubtitlePlan(0, "sub_en-0", "English", "en", false, false);
        Files.writeString(dir.resolve("sub_en-0.vtt"), "WEBVTT\n\n00:01.000 --> 00:04.000\nA caption.\n");
        Files.writeString(
                dir.resolve("sub_en-0.m3u8"),
                """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-PLAYLIST-TYPE:VOD
                #EXT-X-TARGETDURATION:5
                #EXTINF:4.000,
                sub_en-0.vtt
                #EXT-X-ENDLIST
                """);
        Files.writeString(
                dir.resolve("master.m3u8"), writer.render(List.of(RUNG), AAC, List.of(subtitle)));

        assertThat(integrity.inspectPublished(dir).problems()).isEmpty();
        assertThat(integrity.inspectPublished(dir).isSound()).isTrue();
    }

    @Test
    @DisplayName("reads a published ladder from its master alone, and separates media from manifest")
    void inspectsWhatWasPublished(@TempDir Path dir) throws IOException {
        writeHealthyLadder(dir);
        Files.writeString(dir.resolve("master.m3u8"), writer.render(List.of(RUNG), AAC, List.of()));
        assertThat(integrity.inspectPublished(dir).isSound()).isTrue();

        // A manifest fault with the media untouched: the cheap repair, and the caller has to be
        // able to tell it apart without matching on message text.
        Files.writeString(
                dir.resolve("master.m3u8"),
                """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-STREAM-INF:BANDWIDTH=3910000,RESOLUTION=1280x720,CODECS="avc1.4d001f,ec-3",AUDIO="aud"
                720p.m3u8
                """);
        LadderReport manifestOnly = integrity.inspectPublished(dir);
        assertThat(manifestOnly.isSound()).isFalse();
        assertThat(manifestOnly.mediaIntact()).isTrue();

        // And a media fault, which costs an encode.
        Files.delete(dir.resolve("720p_000.m4s"));
        assertThat(integrity.inspectPublished(dir).mediaIntact()).isFalse();
    }

    @Test
    void reportsAMissingMasterRatherThanVouchingForTheMedia(@TempDir Path dir) throws IOException {
        writeHealthyLadder(dir);

        LadderReport report = integrity.inspectPublished(dir);

        assertThat(report.problems()).anyMatch(p -> p.contains("master.m3u8 is missing"));
        // Nothing was inspected, so nothing can be claimed about the media.
        assertThat(report.mediaIntact()).isFalse();
    }

    @Test
    @DisplayName("reads the published audio back, so a repair can tell it could do better")
    void reportsWhatAudioWasPublished(@TempDir Path dir) throws IOException {
        writeHealthyLadder(dir);
        EncodedAudio eac3 = new EncodedAudio(
                AudioPlan.copy(new ProbedAudio(0, "eac3", null, 6, 48000, "eng", null, true), "no decoder"),
                640_000,
                700_000);
        Files.writeString(dir.resolve("master.m3u8"), writer.render(List.of(RUNG), eac3, List.of()));

        LadderReport report = integrity.inspectPublished(dir);

        assertThat(report.isSound()).isTrue();
        assertThat(report.audio()).isPresent();
        assertThat(report.audio().orElseThrow().codecs()).isEqualTo("ec-3");
        assertThat(report.audio().orElseThrow().channels()).isEqualTo(6);

        // A host that has since gained a decoder would produce AAC — worth rebuilding.
        assertThat(report.audioWouldImproveTo("mp4a.40.2", 6)).isTrue();
        // And once it has, the same question answers no. Idempotent by construction.
        assertThat(report.audioWouldImproveTo("ec-3", 6)).isFalse();
    }

    @Test
    @DisplayName("a stereo fold of a surround source is worth rebuilding too")
    void seesAnUpgradeInChannelCount(@TempDir Path dir) throws IOException {
        writeHealthyLadder(dir);
        Files.writeString(dir.resolve("master.m3u8"), writer.render(List.of(RUNG), AAC, List.of()));

        LadderReport report = integrity.inspectPublished(dir);

        // Published stereo AAC by a version of this service that folded everything to two
        // channels; the same source would now keep six.
        assertThat(report.audio().orElseThrow().channels()).isEqualTo(2);
        assertThat(report.audioWouldImproveTo("mp4a.40.2", 6)).isTrue();
        assertThat(report.audioWouldImproveTo("mp4a.40.2", 2)).isFalse();
    }
}
