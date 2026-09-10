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
    void mapsProfileAndLevelToTheCodecString(String profile, int level, String expected) {
        assertThat(ProbedVideo.measured(true, profile, level).codecs()).isEqualTo(expected);
    }

    @Test
    @DisplayName("names only the video, because the audio it plays with is not this rung's to know")
    void neverAppendsAnAudioCodec() {
        // Audio lives in its own rendition group now, so a variant's own probe reports video only.
        // Joining the two is the master playlist's job — it is the only thing that knows which group
        // a variant was pointed at, and whether that group turned out to be AAC or a copied ec-3.
        assertThat(ProbedVideo.measured(true, "Main", 31).codecs()).isEqualTo("avc1.4d001f");
        assertThat(ProbedVideo.measured(false, "Main", 31).codecs()).isEqualTo("avc1.4d001f");
    }

    @Test
    void fallsBackToMainForAProfileItDoesNotRecognise() {
        // An unknown profile should still yield a syntactically valid, plausible string rather than
        // a malformed attribute that a player cannot parse at all.
        assertThat(ProbedVideo.measured(true, "Some Future Profile", 31).codecs()).isEqualTo("avc1.4d001f");
        assertThat(ProbedVideo.measured(true, null, 31).codecs()).isEqualTo("avc1.4d001f");
    }
}
