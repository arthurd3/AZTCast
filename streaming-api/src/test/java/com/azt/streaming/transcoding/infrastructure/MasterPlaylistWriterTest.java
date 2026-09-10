package com.azt.streaming.transcoding.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.azt.streaming.transcoding.domain.AudioPlan;
import com.azt.streaming.transcoding.domain.AudioPreferences;
import com.azt.streaming.transcoding.domain.EncodedAudio;
import com.azt.streaming.transcoding.domain.EncodedRendition;
import com.azt.streaming.transcoding.domain.HlsRendition;
import com.azt.streaming.transcoding.domain.ProbedAudio;
import com.azt.streaming.transcoding.domain.SubtitlePlan;
import com.azt.streaming.transcoding.domain.UndecodableAudioPolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MasterPlaylistWriterTest {

    // Two rungs whose measured codec strings differ — which is the whole point: a single hardcoded
    // value was correct only for 720p. The bandwidths are measured too, so they are not the round
    // numbers the ladder asked for.
    private static final List<EncodedRendition> LADDER = List.of(
            new EncodedRendition(new HlsRendition("720p", 1280, 720, 3000), "avc1.4d001f", 2_900_000, 3_210_000),
            new EncodedRendition(new HlsRendition("240p", 426, 240, 800), "avc1.4d0015", 480_000, 535_000));

    private static final ProbedAudio STEREO_AAC = new ProbedAudio(0, "aac", "LC", 2, 48000, "eng", null, true);
    private static final ProbedAudio SURROUND_EAC3 = new ProbedAudio(0, "eac3", null, 6, 48000, "por", null, true);

    private static final AudioPreferences PREFERENCES =
            new AudioPreferences(List.of("aac"), 0, 0, 64, 512, UndecodableAudioPolicy.PASSTHROUGH);

    private static final EncodedAudio AAC_AUDIO =
            new EncodedAudio(AudioPlan.encode(STEREO_AAC, "aac", PREFERENCES), 128_000, 130_000);

    private final MasterPlaylistWriter writer = new MasterPlaylistWriter();

    private String render() {
        return writer.render(LADDER, AAC_AUDIO, List.of());
    }

    @Test
    @DisplayName("advertises what the segments actually weighed, plus the audio they play with")
    void bandwidthIsMeasuredAndIncludesTheSharedAudio() {
        // The old playlist advertised 3000000 — the video bitrate the ladder asked for, with the
        // audio ignored. Both halves were wrong: nothing rate-controlled a copied rung, and
        // libopenh264 warns on every run that it cannot hit a target bitrate at all.
        assertThat(render())
                .contains("BANDWIDTH=3340000") // 3_210_000 measured peak + 130_000 audio peak
                .contains("AVERAGE-BANDWIDTH=3028000") // 2_900_000 + 128_000
                .doesNotContain("BANDWIDTH=3000000");
    }

    @Test
    void advertisesTheCodecsMeasuredForEachRungRatherThanOneConstant() {
        // 240p really does encode to level 2.1, not 3.1. Advertising 3.1 for it — as a single
        // hardcoded string did — tells the player to provision a decoder for a stream it will
        // never receive, and mis-sizes its capability check.
        assertThat(render())
                .contains("CODECS=\"avc1.4d001f,mp4a.40.2\"")
                .contains("CODECS=\"avc1.4d0015,mp4a.40.2\"");
    }

    @Test
    @DisplayName("names the audio group's real codec, not the one it wishes it had")
    void advertisesACopiedTrackHonestly() {
        // A build with no E-AC-3 decoder copies the track through. Claiming mp4a.40.2 for it would
        // have every browser provision an AAC decoder and then fail on the first segment; ec-3 tells
        // the ones that cannot play it so before they fetch a byte.
        EncodedAudio copied = new EncodedAudio(AudioPlan.copy(SURROUND_EAC3, "no decoder"), 640_000, 700_000);
        String playlist = writer.render(LADDER, copied, List.of());

        assertThat(playlist)
                .contains("CODECS=\"avc1.4d001f,ec-3\"")
                .contains("TYPE=AUDIO,GROUP-ID=\"aud\"")
                .contains("LANGUAGE=\"pt\"")
                .contains("CHANNELS=\"6\"")
                .contains("URI=\"audio.m3u8\"");
    }

    @Test
    @DisplayName("points every variant at the one audio rendition")
    void joinsEveryVariantToTheAudioGroup() {
        String playlist = render();

        assertThat(playlist.lines().filter(line -> line.startsWith("#EXT-X-MEDIA:TYPE=AUDIO")))
                .as("exactly one audio rendition, shared")
                .hasSize(1);
        assertThat(playlist.lines().filter(line -> line.contains("AUDIO=\"aud\""))).hasSize(2);
    }

    @Test
    void omitsTheAudioGroupEntirelyForASilentLadder() {
        String playlist = writer.render(LADDER, EncodedAudio.none(), List.of());

        assertThat(playlist).doesNotContain("EXT-X-MEDIA", "AUDIO=");
        assertThat(playlist).contains("CODECS=\"avc1.4d001f\"");
    }

    @Test
    @DisplayName("offers subtitles without turning them on")
    void advertisesSubtitlesWithoutDefaultingThemOn() {
        List<SubtitlePlan> subtitles = List.of(
                new SubtitlePlan(0, "sub_en-0", "English", "en", false, false),
                new SubtitlePlan(1, "sub_en-sdh-1", "English (SDH)", "en", false, true));
        String playlist = writer.render(LADDER, AAC_AUDIO, subtitles);

        assertThat(playlist.lines().filter(line -> line.startsWith("#EXT-X-MEDIA:TYPE=SUBTITLES"))).hasSize(2);
        // DEFAULT=NO on every track: burning captions onto a viewer who did not ask for them is
        // worse than making them click once. AUTOSELECT=YES still lets a language preference win.
        assertThat(playlist.lines().filter(line -> line.startsWith("#EXT-X-MEDIA:TYPE=SUBTITLES")))
                .allMatch(line -> line.contains("DEFAULT=NO") && line.contains("AUTOSELECT=YES"));
        assertThat(playlist).contains("SUBTITLES=\"subs\"");
        // Only the SDH track carries a characteristic, and the others carry no empty attribute.
        assertThat(playlist).contains("CHARACTERISTICS=\"public.accessibility.describes-music-and-sound\"");
        assertThat(playlist).doesNotContain("CHARACTERISTICS=\"\"");
    }

    @Test
    void keepsVariantUrisRelativeToTheMaster() {
        // hls.js resolves these against the master URL, so absolute paths would pin the API to one
        // mount point.
        assertThat(render().lines())
                .contains("720p.m3u8", "240p.m3u8")
                .noneMatch(line -> line.startsWith("/") || line.startsWith("http"));
    }

    @Test
    void emitsOneStreamInfPerRungInLadderOrder() {
        List<String> lines = render().lines().toList();

        // Version 7, not 3: the variants are fMP4 and use EXT-X-MAP.
        assertThat(lines).startsWith("#EXTM3U", "#EXT-X-VERSION:7", "#EXT-X-INDEPENDENT-SEGMENTS");
        assertThat(lines.stream().filter(l -> l.startsWith("#EXT-X-STREAM-INF")).count()).isEqualTo(2);
        assertThat(lines.indexOf("720p.m3u8")).isLessThan(lines.indexOf("240p.m3u8"));
        assertThat(render()).contains("RESOLUTION=1280x720", "RESOLUTION=426x240");
    }

    @Test
    void writesTheMasterUnderTheVideoDirectory(@TempDir Path videoDirectory) throws IOException {
        writer.write(videoDirectory, render());

        assertThat(videoDirectory.resolve("master.m3u8")).exists().content().isEqualTo(render());
    }

    @Test
    @DisplayName("offers every rung a second time, without audio, when only Apple can decode it")
    void advertisesAVideoOnlyFamilyForAppleOnlyAudio() {
        // The defect: a variant's CODECS describes the whole combination, so `avc1.…,ec-3` is
        // unsupported in Chrome even though the video half is fine. hls.js filtered away all five
        // rungs and reported manifestIncompatibleCodecsError — the video did not play at all.
        EncodedAudio copied = new EncodedAudio(AudioPlan.copy(SURROUND_EAC3, "no decoder"), 640_000, 700_000);
        String playlist = writer.render(LADDER, copied, List.of());
        List<String> variants = playlist.lines().filter(l -> l.startsWith("#EXT-X-STREAM-INF")).toList();

        assertThat(variants).hasSize(4);
        // The ones carrying audio come first: it is the only nudge the format allows towards a
        // player that can decode both.
        assertThat(variants.subList(0, 2)).allMatch(l -> l.contains(",ec-3\"") && l.contains("AUDIO=\"aud\""));
        assertThat(variants.subList(2, 4))
                .allMatch(l -> !l.contains("ec-3") && !l.contains("AUDIO=") && l.contains("CODECS=\"avc1."));

        // Same media, referenced a second way. Not one extra byte on disk.
        assertThat(playlist.lines().filter("720p.m3u8"::equals)).hasSize(2);
        // A video-only variant costs less, and says so.
        assertThat(playlist).contains("BANDWIDTH=3910000").contains("BANDWIDTH=3210000");
    }

    @Test
    @DisplayName("says nothing twice when the audio plays everywhere")
    void doesNotDuplicateForAac() {
        String playlist = render();

        assertThat(playlist.lines().filter(l -> l.startsWith("#EXT-X-STREAM-INF"))).hasSize(2);
        assertThat(playlist.lines().filter("720p.m3u8"::equals)).hasSize(1);
    }

    @Test
    void keepsSubtitlesOnBothFamilies() {
        // Subtitles are their own group and their own segments; nothing about them depends on
        // which audio the player ended up with.
        EncodedAudio copied = new EncodedAudio(AudioPlan.copy(SURROUND_EAC3, "no decoder"), 640_000, 700_000);
        List<SubtitlePlan> subtitles = List.of(new SubtitlePlan(0, "sub_en-0", "English", "en", false, false));

        assertThat(writer.render(LADDER, copied, subtitles).lines().filter(l -> l.startsWith("#EXT-X-STREAM-INF")))
                .allMatch(l -> l.contains("SUBTITLES=\"subs\""));
    }

    @Test
    @DisplayName("a 5.1 AAC rendition is advertised once, with its real channel count")
    void multichannelAacNeedsNoSecondFamily() {
        // The dual advertisement exists for audio only Apple decodes. AAC is mp4a.40.2 whatever
        // its layout, so a 5.1 AAC ladder is one family — and the browser folds it down itself.
        ProbedAudio surround = new ProbedAudio(0, "eac3", null, 6, 48000, "eng", null, true);
        EncodedAudio aac51 =
                new EncodedAudio(AudioPlan.encode(surround, "aac", PREFERENCES), 384_000, 400_000);
        String playlist = writer.render(LADDER, aac51, List.of());

        assertThat(playlist.lines().filter(l -> l.startsWith("#EXT-X-STREAM-INF"))).hasSize(2);
        assertThat(playlist).contains("CHANNELS=\"6\"").contains("CODECS=\"avc1.4d001f,mp4a.40.2\"");
        assertThat(playlist.lines().filter("720p.m3u8"::equals)).hasSize(1);
    }
}
