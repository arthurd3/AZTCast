package com.azt.streaming.transcoding.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VariantPlaylistTest {

    /** Captured from a variant this project's own ffmpeg command wrote. */
    private static final List<String> REAL_VARIANT = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:5
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-INDEPENDENT-SEGMENTS
            #EXT-X-MAP:URI="720p_init.mp4"
            #EXTINF:4.004000,
            720p_000.m4s
            #EXTINF:4.004000,
            720p_001.m4s
            #EXTINF:0.451000,
            720p_002.m4s
            #EXT-X-ENDLIST
            """.lines().toList();

    @Test
    @DisplayName("reads the init segment, which is the part a naive parser drops")
    void readsTheInitSegment() {
        // VariantWeigher skipped every line starting with '#', so it never saw EXT-X-MAP — and the
        // init segment is the one file without which a variant cannot be decoded at all.
        VariantPlaylist parsed = VariantPlaylist.parse(REAL_VARIANT);

        assertThat(parsed.initSegment()).contains("720p_init.mp4");
        assertThat(parsed.referencedFiles())
                .containsExactly("720p_init.mp4", "720p_000.m4s", "720p_001.m4s", "720p_002.m4s");
    }

    @Test
    void pairsEachSegmentWithItsOwnDuration() {
        VariantPlaylist parsed = VariantPlaylist.parse(REAL_VARIANT);

        assertThat(parsed.segments())
                .extracting(VariantPlaylist.Segment::uri)
                .containsExactly("720p_000.m4s", "720p_001.m4s", "720p_002.m4s");
        // The last segment of any real encode is short; assuming the target duration for it would
        // understate its bitrate.
        assertThat(parsed.segments().getLast().durationSeconds()).isEqualTo(0.451);
        assertThat(parsed.totalSeconds()).isEqualTo(8.459);
        assertThat(parsed.complete()).isTrue();
    }

    @Test
    @DisplayName("a playlist ffmpeg never finished is not complete")
    void detectsAMissingEndlist() {
        // What a killed encode leaves behind. A player waits for a segment that is never coming.
        List<String> truncated = REAL_VARIANT.subList(0, REAL_VARIANT.size() - 1);

        assertThat(VariantPlaylist.parse(truncated).complete()).isFalse();
        assertThat(VariantPlaylist.parse(truncated).segments()).hasSize(3);
    }

    @Test
    void ignoresTagsItDoesNotUnderstandRatherThanTreatingThemAsSegments() {
        VariantPlaylist parsed = VariantPlaylist.parse(List.of(
                "#EXTM3U",
                "#EXT-X-PROGRAM-DATE-TIME:2026-09-10T13:43:27Z",
                "#EXT-X-DISCONTINUITY",
                "#EXTINF:4.000000,",
                "a_000.m4s",
                "#EXT-X-ENDLIST"));

        assertThat(parsed.segments()).hasSize(1);
        assertThat(parsed.initSegment()).isEmpty();
    }

    @Test
    void survivesAPlaylistWithNothingUsableInIt() {
        assertThat(VariantPlaylist.parse(List.of()).segments()).isEmpty();
        assertThat(VariantPlaylist.parse(List.of("#EXTM3U")).referencedFiles()).isEmpty();
        // A URI with no EXTINF in front of it is a shape this does not understand; inventing a
        // zero-length segment for it would make an unreadable file look merely empty.
        assertThat(VariantPlaylist.parse(List.of("#EXTM3U", "orphan.m4s")).segments()).isEmpty();
    }
}
