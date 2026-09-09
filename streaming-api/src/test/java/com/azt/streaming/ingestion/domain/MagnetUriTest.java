package com.azt.streaming.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MagnetUriTest {

    private static final String HEX = "0123456789abcdef0123456789abcdef01234567";

    @Test
    @DisplayName("ignores the parts of a magnet link that vary between copies of it")
    void isStableAcrossTrackerAndNameDifferences() {
        // This is the whole reason the class exists. Two links to the same torrent almost never
        // match as strings — the tracker list and display name depend on where the link came from —
        // so deduplicating on the raw URL would look right and never once fire.
        String fromOneSite = "magnet:?xt=urn:btih:" + HEX + "&dn=Big+Buck+Bunny&tr=udp://tracker.one:1337";
        String fromAnother = "magnet:?xt=urn:btih:" + HEX + "&tr=udp://tracker.two:80&tr=udp://tracker.three:6969";

        assertThat(MagnetUri.identity(fromOneSite)).isEqualTo(MagnetUri.identity(fromAnother));
    }

    @Test
    void treatsTheHashAsCaseInsensitive() {
        assertThat(MagnetUri.identity("magnet:?xt=urn:btih:" + HEX.toUpperCase()))
                .isEqualTo(MagnetUri.identity("magnet:?xt=urn:btih:" + HEX));
    }

    @Test
    @DisplayName("base32 and hex spellings of one infohash are one identity")
    void decodesBase32ToTheSameIdentityAsHex() {
        // Both spellings are in common use. Treating them as different torrents would mean
        // downloading the same content twice for no visible reason.
        // base32(hex_decode(HEX)), verified independently.
        String base32 = "AERUKZ4JVPG66AJDIVTYTK6N54ASGRLH";

        assertThat(MagnetUri.identity("magnet:?xt=urn:btih:" + base32))
                .isEqualTo(MagnetUri.identity("magnet:?xt=urn:btih:" + HEX));
    }

    @Test
    void distinguishesDifferentTorrents() {
        assertThat(MagnetUri.identity("magnet:?xt=urn:btih:" + HEX))
                .isNotEqualTo(MagnetUri.identity("magnet:?xt=urn:btih:" + HEX.replace("0123", "9999")));
    }

    @Test
    void givesAnUnparseableLinkItsOwnStableIdentity() {
        // Not one shared bucket for everything malformed: that would make two unrelated bad links
        // deduplicate against each other.
        assertThat(MagnetUri.identity("magnet:?dn=no-hash-here"))
                .isEqualTo(MagnetUri.identity("magnet:?dn=no-hash-here"))
                .isNotEqualTo(MagnetUri.identity("magnet:?dn=something-else"));
    }
}
