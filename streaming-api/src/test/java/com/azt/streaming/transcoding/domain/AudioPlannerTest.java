package com.azt.streaming.transcoding.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AudioPlannerTest {

    /** The shipped policy: keep whatever the source had, at 64 kbps a channel. */
    private static final AudioPreferences PRESERVE =
            new AudioPreferences(List.of("libfdk_aac", "aac"), 0, 0, 64, 512, UndecodableAudioPolicy.PASSTHROUGH);

    /** A build that can decode everything and encode AAC — the happy host. */
    private static final FfmpegSupport FULL = new FfmpegSupport(
            Set.of("libx264", "aac", "libfdk_aac"),
            Set.of("h264", "aac", "ac3", "eac3", "dts", "truehd", "mp3", "flac", "opus"),
            Set.of(),
            true);

    /** Fedora's patent-free build: H.264 and AAC, but no E-AC-3 decoder. This is the one that shipped. */
    private static final FfmpegSupport NO_EAC3 = new FfmpegSupport(
            Set.of("libopenh264", "aac", "libfdk_aac"), Set.of("h264", "aac", "ac3"), Set.of(), true);

    private static ProbedSource sourceWith(ProbedAudio... tracks) {
        return new ProbedSource(
                true, "h264", "High", 40, 1920, 1080, 23.976, 4459, 1357.8, List.of(tracks), List.of());
    }

    private static ProbedAudio track(String codec, String profile, int channels) {
        return new ProbedAudio(0, codec, profile, channels, 48000, "eng", null, true);
    }

    @Test
    @DisplayName("copies a stereo AAC-LC track, because nothing beats not re-encoding it")
    void copiesPlainStereoAac() {
        AudioPlan plan = AudioPlanner.plan(sourceWith(track("aac", "LC", 2)), PRESERVE, FULL);

        assertThat(plan.mode()).isEqualTo(AudioPlan.Mode.COPY);
        assertThat(plan.codecs()).isEqualTo("mp4a.40.2");
        assertThat(plan.reason()).isNull();
    }

    @Test
    @DisplayName("copies a 5.1 AAC track instead of folding it to stereo")
    void copiesSurroundAac() {
        // This used to be re-encoded, purely to fold six channels into two. Nothing about that
        // trade was good: it cost CPU, lost a generation, and threw away four channels for a
        // downmix the viewer's own output device does better.
        AudioPlan plan = AudioPlanner.plan(sourceWith(track("aac", "LC", 6)), PRESERVE, FULL);

        assertThat(plan.mode()).isEqualTo(AudioPlan.Mode.COPY);
        assertThat(plan.channels()).isEqualTo(6);
    }

    @Test
    @DisplayName("keeps 5.1 when it has to re-encode, and scales the bitrate to it")
    void encodesSurroundAtSurroundBitrates() {
        // 64 kbps a channel: 384k at 5.1, which is what Apple's authoring specification asks for.
        // The old fixed 128 was a stereo number applied to everything.
        AudioPlan plan = AudioPlanner.plan(sourceWith(track("eac3", null, 6)), PRESERVE, FULL);

        assertThat(plan.mode()).isEqualTo(AudioPlan.Mode.ENCODE);
        assertThat(plan.channels()).isEqualTo(6);
        assertThat(plan.bitrateKbps()).isEqualTo(384);
        assertThat(plan.codecs()).isEqualTo("mp4a.40.2");
    }

    @Test
    @DisplayName("still downmixes when an operator asks for a fixed layout")
    void honoursAnExplicitChannelCount() {
        AudioPreferences stereo =
                new AudioPreferences(List.of("aac"), 2, 0, 64, 512, UndecodableAudioPolicy.PASSTHROUGH);

        AudioPlan plan = AudioPlanner.plan(sourceWith(track("aac", "LC", 6)), stereo, FULL);

        assertThat(plan.mode()).isEqualTo(AudioPlan.Mode.ENCODE);
        assertThat(plan.channels()).isEqualTo(2);
        assertThat(plan.bitrateKbps()).isEqualTo(128);
    }

    @Test
    @DisplayName("caps at what AAC can carry")
    void capsChannelsAtEight() {
        // A 9.1 source is rare and real. Asking ffmpeg for ten channels of AAC fails the encode.
        AudioPlan plan = AudioPlanner.plan(sourceWith(track("truehd", null, 10)), PRESERVE, FULL);

        assertThat(plan.channels()).isEqualTo(8);
        assertThat(plan.bitrateKbps()).isEqualTo(512); // the ceiling, not 8 * 64
    }

    @Test
    @DisplayName("keeps the source's sample rate rather than resampling to 48 kHz")
    void keepsTheSourceSampleRate() {
        ProbedAudio cd = new ProbedAudio(0, "mp3", null, 2, 44100, "eng", null, true);

        AudioPlan plan = AudioPlanner.plan(sourceWith(cd), PRESERVE, FULL);

        assertThat(plan.sampleRate()).isEqualTo(44100);
    }

    @Test
    @DisplayName("re-encodes an HE-AAC track, because mp4a.40.2 would be a lie about it")
    void encodesUnusualAacProfiles() {
        assertThat(AudioPlanner.plan(sourceWith(track("aac", "HE-AAC", 2)), PRESERVE, FULL).mode())
                .isEqualTo(AudioPlan.Mode.ENCODE);
        // An unreported profile counts as unusual for the same reason.
        assertThat(AudioPlanner.plan(sourceWith(track("aac", null, 2)), PRESERVE, FULL).mode())
                .isEqualTo(AudioPlan.Mode.ENCODE);
    }

    @Test
    @DisplayName("encodes E-AC-3 to AAC on a build that can decode it")
    void encodesEac3WhereItCan() {
        AudioPlan plan = AudioPlanner.plan(sourceWith(track("eac3", null, 6)), PRESERVE, FULL);

        assertThat(plan.mode()).isEqualTo(AudioPlan.Mode.ENCODE);
        assertThat(plan.encoder()).isEqualTo("libfdk_aac");
    }

    @Test
    @DisplayName("copies E-AC-3 through when this build has no decoder for it")
    void passesEac3ThroughWhenItCannotDecodeIt() {
        // The defect this class exists for. Before it, this source produced
        // `Decoding requested, but no decoder found for: eac3` and a failed ingestion — after the
        // download had already been paid for.
        AudioPlan plan = AudioPlanner.plan(sourceWith(track("eac3", null, 6)), PRESERVE, NO_EAC3);

        assertThat(plan.mode()).isEqualTo(AudioPlan.Mode.COPY);
        assertThat(plan.channels()).isEqualTo(6);
        // Honest about what it is: a browser that cannot play EC-3 learns it from the manifest.
        assertThat(plan.codecs()).isEqualTo("ec-3");
        assertThat(plan.reason()).contains("no eac3 decoder");
    }

    @Test
    @DisplayName("drops audio it can neither decode nor package")
    void dropsAudioThatCannotTravelEitherWay() {
        // DTS: no decoder here, and fMP4 will not carry it. Video-only beats no video.
        AudioPlan plan = AudioPlanner.plan(sourceWith(track("dts", "DTS-HD MA", 8)), PRESERVE, NO_EAC3);

        assertThat(plan.mode()).isEqualTo(AudioPlan.Mode.NONE);
        assertThat(plan.reason()).contains("no dts decoder").contains("video only");
    }

    @Test
    void dropsUndecodableAudioOutrightUnderTheDropPolicy() {
        AudioPreferences drop = new AudioPreferences(List.of("aac"), 0, 0, 64, 512, UndecodableAudioPolicy.DROP);

        assertThat(AudioPlanner.plan(sourceWith(track("eac3", null, 6)), drop, NO_EAC3).mode())
                .isEqualTo(AudioPlan.Mode.NONE);
    }

    @Test
    @DisplayName("fails in milliseconds, with the remedy in the message, under the fail policy")
    void failsFastWhenConfiguredTo() {
        AudioPreferences fail = new AudioPreferences(List.of("aac"), 0, 0, 64, 512, UndecodableAudioPolicy.FAIL);

        assertThatThrownBy(() -> AudioPlanner.plan(sourceWith(track("eac3", null, 6)), fail, NO_EAC3))
                .isInstanceOf(TranscodingException.class)
                .hasMessageContaining("no eac3 decoder")
                .hasMessageContaining("passthrough");
    }

    @Test
    void producesNoAudioForASourceThatCarriesNone() {
        AudioPlan plan = AudioPlanner.plan(sourceWith(), PRESERVE, FULL);

        assertThat(plan.present()).isFalse();
        assertThat(plan.reason()).contains("no audio track");
    }

    @Test
    @DisplayName("an unreadable capability listing changes nothing, rather than refusing everything")
    void unknownCapabilitiesAreOptimistic() {
        // A stub binary, or a future ffmpeg whose table this parser does not recognise. Guessing
        // "no" would turn one unreadable listing into a service that refuses every file.
        AudioPlan plan = AudioPlanner.plan(sourceWith(track("eac3", null, 6)), PRESERVE, FfmpegSupport.unknown());

        assertThat(plan.mode()).isEqualTo(AudioPlan.Mode.ENCODE);
    }

    @Test
    @DisplayName("plans against the track the container marks default, not whichever came first")
    void plansForTheDefaultTrack() {
        ProbedAudio commentary = new ProbedAudio(0, "aac", "LC", 2, 48000, "eng", "Commentary", false);
        ProbedAudio feature = new ProbedAudio(1, "aac", "LC", 2, 48000, "por", null, true);

        AudioPlan plan = AudioPlanner.plan(sourceWith(commentary, feature), PRESERVE, FULL);

        assertThat(plan.sourceIndex()).isEqualTo(1);
        assertThat(plan.language()).isEqualTo("pt");
    }
}
