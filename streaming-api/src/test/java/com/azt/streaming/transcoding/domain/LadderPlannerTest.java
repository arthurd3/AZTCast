package com.azt.streaming.transcoding.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LadderPlannerTest {

    /** The shipped ladder, so these assertions are about the configuration people actually run. */
    private static final List<HlsRendition> LADDER = List.of(
            new HlsRendition("1080p", 1920, 1080, 5000),
            new HlsRendition("720p", 1280, 720, 3000),
            new HlsRendition("480p", 854, 480, 1500),
            new HlsRendition("360p", 640, 360, 900),
            new HlsRendition("240p", 426, 240, 500));

    static ProbedSource source(String videoCodec, int width, int height, String audioCodec, String audioProfile) {
        List<ProbedAudio> audio = audioCodec == null
                ? List.of()
                : List.of(new ProbedAudio(0, audioCodec, audioProfile, 2, 48000, "eng", null, true));
        return new ProbedSource(true, videoCodec, "High", 40, width, height, 23.976, 6000, 1357.8, audio, List.of());
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
        ProbedSource unmeasured =
                new ProbedSource(true, "h264", "Main", 31, 0, 0, 0, 0, 0, List.of(), List.of());

        assertThat(names(LadderPlanner.plan(LADDER, unmeasured)))
                .containsExactly("1080p", "720p", "480p", "360p", "240p");
    }

    @Test
    @DisplayName("advertises the source's own bitrate for the rung it copied")
    void copyRungCarriesTheSourceBitrate() {
        List<PlannedRendition> planned = LadderPlanner.plan(LADDER, source("h264", 1920, 1080, "aac", "LC"));
        HlsRendition copied = planned.getFirst().rendition();

        // A first estimate only: the container bitrate also covers an audio track that now travels
        // in its own rendition, so what the playlist actually advertises is weighed off the
        // segments once they exist. This is what it falls back to when that weighing fails.
        assertThat(copied.videoBitrateKbps()).isEqualTo(6000);
        assertThat(copied.resolution()).isEqualTo("1920x1080");
    }
}
