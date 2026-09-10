package com.azt.streaming.acquisition.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading the client out of a peer_id.
 *
 * <p>The rule that matters most is the last one: an id this does not understand must decode to
 * null, never to a guess. The client column is read as evidence about a swarm, and a wrong name is
 * worse than a blank — a blank is obviously absent, where a wrong name is quietly believed.
 */
class PeerIdDecoderTest {

    /** A peer_id is 20 bytes; only the prefix carries meaning, the rest is random. */
    private static byte[] peerId(String prefix) {
        byte[] id = new byte[20];
        byte[] bytes = prefix.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(bytes, 0, id, 0, bytes.length);
        return id;
    }

    @Test
    void readsTheAzureusConvention() {
        assertThat(PeerIdDecoder.decode(peerId("-qB4650-"))).isEqualTo("qBittorrent 4.6.5");
        assertThat(PeerIdDecoder.decode(peerId("-TR4060-"))).isEqualTo("Transmission 4.0.6");
        assertThat(PeerIdDecoder.decode(peerId("-DE2100-"))).isEqualTo("Deluge 2.1.0");
    }

    @Test
    @DisplayName("a non-zero fourth digit is a build number and is kept")
    void keepsTheBuildDigitWhenItSaysSomething() {
        // Clients pad the build position, so "3.5.5.0" and "3.5.5" are the same release written two
        // ways; only a non-zero build is worth the extra segment.
        assertThat(PeerIdDecoder.decode(peerId("-TR3550-"))).isEqualTo("Transmission 3.5.5");
        assertThat(PeerIdDecoder.decode(peerId("-TR3553-"))).isEqualTo("Transmission 3.5.5.3");
    }

    @Test
    @DisplayName("an unlisted client code still yields its code rather than nothing")
    void fallsBackToTheRawCode() {
        // The registry of two-letter codes is folklore, not a standard, so it will always be
        // incomplete. "ZZ 1.0.0" is honest and more useful than a blank.
        assertThat(PeerIdDecoder.decode(peerId("-ZZ1000-"))).isEqualTo("ZZ 1.0.0");
    }

    @Test
    void passesThroughNonNumericVersionsUntouched() {
        // Some clients use base-62 to fit a larger number into one character. Translating those as
        // decimal digits would invent a version.
        assertThat(PeerIdDecoder.decode(peerId("-qB50A0-"))).isEqualTo("qBittorrent 50A0");
    }

    @Test
    void readsTheShadowConvention() {
        assertThat(PeerIdDecoder.decode(peerId("T03C-"))).isEqualTo("BitTornado");
        assertThat(PeerIdDecoder.decode(peerId("A234-"))).isEqualTo("ABC");
    }

    @Test
    @DisplayName("anything unrecognised decodes to null, never to a guess")
    void refusesToGuess() {
        assertThat(PeerIdDecoder.decode(peerId("        "))).isNull();
        assertThat(PeerIdDecoder.decode(peerId("M4-20-8-"))).isNull();
        assertThat(PeerIdDecoder.decode(new byte[0])).isNull();
        assertThat(PeerIdDecoder.decode(null)).isNull();
    }
}
