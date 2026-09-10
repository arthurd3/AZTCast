package com.azt.streaming.transcoding.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LadderPlannerTest {

    /** The shipped ladder, so these assertions are about the configuration people actually run. */
    private static final List<HlsRendition> LADDER = List.of(
            new HlsRendition("1080p", 1920, 1080, 5000, 128),
            new HlsRendition("720p", 1280, 720, 3000, 128),
            new HlsRendition("480p", 854, 480, 1500, 96),
            new HlsRendition("360p", 640, 360, 900, 96),
            new HlsRendition("240p", 426, 240, 500, 64));

    private static ProbedVideo source(String videoCodec, int width, int height, String audioCodec, String audioProfile) {
        return new ProbedVideo(true, audioCodec != null, "High", 40, videoCodec, audioCodec, audioProfile, width, height, 6000);
    }

    private static List<String> names(List<PlannedRendition> planned) {
        return planned.stream().map(rung -> rung.rendition().name()).toList();
    }

    @Test
    @DisplayName("copies the top rung and encodes only what sits below it")
    void copiesTheTopRung() {
        List<PlannedRendition> planned = LadderPlanner.plan(LADDER, source("h264", 1920, 1080, "aac", "LC"));

        assertThat(names(planned)).containsExactly("1080p", "720p", "480p", "360p", "240p");
        assertThat(planned.getFirst().copyVideo()).isTrue();
        assertThat(planned.getFirst().copyAudio()).isTrue();
        // Exactly one copy. A second would mean two rungs of identical content.
        assertThat(planned.stream().filter(PlannedRendition::copyVideo)).hasSize(1);
    }

    @Test
    @DisplayName("never invents a rung taller than the source")
    void neverUpscales() {
        // The defect this rule exists for: a 480p torrent used to be scaled up to 1080p and 720p,
        // which are the two most expensive rungs on the ladder and are both strictly worse than the
        // picture they were invented from.
        List<PlannedRendition> planned = LadderPlanner.plan(LADDER, source("h264", 854, 480, "aac", "LC"));

        assertThat(names(planned)).containsExactly("480p", "360p", "240p");
        assertThat(names(planned)).doesNotContain("1080p", "720p");
    }

    @Test
    @DisplayName("encodes the whole fitting ladder when the source cannot be copied")
    void encodesEverythingForANonH264Source() {
        List<PlannedRendition> planned = LadderPlanner.plan(LADDER, source("hevc", 1920, 1080, "aac", "LC"));

        // 1080p is kept, but encoded: an HEVC top rung would be a rung many browsers refuse.
        assertThat(names(planned)).containsExactly("1080p", "720p", "480p", "360p", "240p");
        assertThat(planned).allMatch(rung -> !rung.copyVideo());
    }

    @Test
    @DisplayName("re-encodes audio that is not AAC-LC while still copying the video")
    void reEncodesUnusualAudio() {
        List<PlannedRendition> planned = LadderPlanner.plan(LADDER, source("h264", 1920, 1080, "aac", "HE-AAC"));

        // The CODECS string hardcodes mp4a.40.2, so copying HE-AAC would have the playlist claim
        // AAC-LC for something that is not. Re-encoding the audio costs almost nothing.
        assertThat(planned.getFirst().copyVideo()).isTrue();
        assertThat(planned.getFirst().copyAudio()).isFalse();
    }

    @Test
    @DisplayName("drops a configured rung that is a near-duplicate of an anamorphic source")
    void dropsNearDuplicateRungs() {
        // 1082 is not a typo: anamorphic and cropped sources land on heights nothing configured
        // matches. Without the near-duplicate rule this would encode a 1080p rung directly beneath
        // a 1082p copy of the same picture.
        List<PlannedRendition> planned = LadderPlanner.plan(LADDER, source("h264", 1920, 1082, "aac", "LC"));

        assertThat(names(planned)).containsExactly("1082p", "720p", "480p", "360p", "240p");
    }

    @Test
    @DisplayName("keeps the shortest rung for a source shorter than the whole ladder")
    void alwaysProducesSomethingPlayable() {
        // The one upscale worth doing: the alternative is a video with no rung at all.
        List<PlannedRendition> planned = LadderPlanner.plan(LADDER, source("hevc", 320, 180, "aac", "LC"));

        assertThat(names(planned)).containsExactly("240p");
    }

    @Test
    @DisplayName("a copyable source shorter than the ladder needs no encoder at all")
    void copyOnlyLadder() {
        List<PlannedRendition> planned = LadderPlanner.plan(LADDER, source("h264", 320, 180, "aac", "LC"));

        assertThat(names(planned)).containsExactly("180p");
        assertThat(planned.getFirst().copyVideo()).isTrue();
    }

    @Test
    @DisplayName("falls back to the configured ladder when nothing was measured")
    void unmeasuredSourceBuildsWhatWasConfigured() {
        // Probing an output rather than a source reports no dimensions. Guessing from that would be
        // worse than the behaviour this replaced.
        ProbedVideo unmeasured = ProbedVideo.measured(true, "Main", 31);

        assertThat(names(LadderPlanner.plan(LADDER, unmeasured)))
                .containsExactly("1080p", "720p", "480p", "360p", "240p");
    }

    @Test
    @DisplayName("advertises the source's own bitrate for the rung it copied")
    void copyRungCarriesTheSourceBitrate() {
        List<PlannedRendition> planned = LadderPlanner.plan(LADDER, source("h264", 1920, 1080, "aac", "LC"));
        HlsRendition copied = planned.getFirst().rendition();

        // The container bitrate already includes audio, so the rung declares none of its own —
        // otherwise peakBandwidthBps() would count the audio track twice.
        assertThat(copied.videoBitrateKbps()).isEqualTo(6000);
        assertThat(copied.audioBitrateKbps()).isZero();
        assertThat(copied.resolution()).isEqualTo("1920x1080");
    }
}
