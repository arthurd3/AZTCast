package com.azt.streaming.transcoding.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProbedVideoTest {

    @ParameterizedTest(name = "{0} level {1} -> {2}")
    @CsvSource({
        // These five are measured, not derived on paper: they are what libopenh264 actually produced
        // for the 1080p/720p/480p/360p/240p ladder. Only the 720p row matches the single hardcoded
        // "avc1.4d001f" the master playlist used to advertise for every rung.
        "Main, 40, avc1.4d0028",
        "Main, 31, avc1.4d001f",
        "Main, 30, avc1.4d001e",
        "Main, 21, avc1.4d0015",
        "High, 40, avc1.640028",
        "Constrained Baseline, 21, avc1.42e015",
        "Baseline, 30, avc1.42001e",
    })
    @DisplayName("builds the RFC 6381 string from what the encoder actually produced")
    void mapsProfileAndLevelToTheCodecString(String profile, int level, String expectedVideo) {
        ProbedVideo probed = new ProbedVideo(true, true, profile, level);

        assertThat(probed.codecs()).isEqualTo(expectedVideo + ",mp4a.40.2");
    }

    @Test
    void omitsTheAudioCodecWhenThereIsNoAudioTrack() {
        // Advertising mp4a on a rung with no audio is the same class of error as the wrong level:
        // the player provisions a decoder for a stream that never arrives.
        ProbedVideo silent = new ProbedVideo(true, false, "Main", 31);

        assertThat(silent.codecs()).isEqualTo("avc1.4d001f");
    }

    @Test
    void fallsBackToMainForAProfileItDoesNotRecognise() {
        // An unknown profile should still yield a syntactically valid, plausible string rather than
        // a malformed attribute that a player cannot parse at all.
        assertThat(new ProbedVideo(true, true, "Some Future Profile", 31).codecs())
                .isEqualTo("avc1.4d001f,mp4a.40.2");
        assertThat(new ProbedVideo(true, true, null, 31).codecs()).isEqualTo("avc1.4d001f,mp4a.40.2");
    }
}
