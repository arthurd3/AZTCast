package com.azt.streaming.acquisition.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import bt.magnet.MagnetUri;
import bt.magnet.MagnetUriParser;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MagnetTrackerInjectorTest {

    private static final String INFOHASH = "c9e15763f722f23e98a29decdfae341b98d53056";

    private static final List<String> EXTRA =
            List.of("udp://tracker.example:1337/announce", "udp://other.example:6969/announce");

    private static MagnetUri parse(String magnet) {
        return MagnetUriParser.lenientParser().parse(magnet);
    }

    @Test
    @DisplayName("adds the configured trackers to a magnet that carries none")
    void addsTrackersToABareMagnet() {
        // The case the whole class exists for: a bare magnet leaves DHT to find the swarm alone.
        MagnetUri augmented = new MagnetTrackerInjector(EXTRA, List.of()).augment(parse("magnet:?xt=urn:btih:" + INFOHASH));

        assertThat(augmented.getTrackerUrls()).containsExactlyInAnyOrderElementsOf(EXTRA);
        assertThat(augmented.getTorrentId().toString()).isEqualTo(INFOHASH);
    }

    @Test
    @DisplayName("adds to the magnet's own trackers rather than replacing them")
    void keepsTheMagnetsOwnTrackers() {
        // Losing the publisher's tracker would be a straight downgrade: it is likelier to hold this
        // particular swarm than a general-purpose public one. Order is not asserted, and cannot be:
        // MagnetUri.Builder collects trackers into a HashSet, so nothing that goes through it can
        // control what gets announced to first.
        MagnetUri augmented = new MagnetTrackerInjector(EXTRA, List.of())
                .augment(parse("magnet:?xt=urn:btih:" + INFOHASH + "&tr=udp%3A%2F%2Fpublisher.example%3A80%2Fannounce"));

        assertThat(augmented.getTrackerUrls())
                .containsExactlyInAnyOrder(
                        "udp://publisher.example:80/announce",
                        "udp://tracker.example:1337/announce",
                        "udp://other.example:6969/announce");
    }

    @Test
    @DisplayName("does not announce to the same tracker twice")
    void deduplicates() {
        MagnetUri augmented = new MagnetTrackerInjector(List.of("UDP://Tracker.Example:1337/announce"), List.of())
                .augment(parse("magnet:?xt=urn:btih:" + INFOHASH + "&tr=udp%3A%2F%2Ftracker.example%3A1337%2Fannounce"));

        assertThat(augmented.getTrackerUrls()).hasSize(1);
    }

    @Test
    @DisplayName("preserves the display name, which is the only name there is before metadata")
    void preservesTheDisplayName() {
        // Dropped, and the library loses the only human-readable name it has until the torrent's
        // metadata arrives — which on a slow swarm can be minutes.
        MagnetUri augmented = new MagnetTrackerInjector(EXTRA, List.of())
                .augment(parse("magnet:?xt=urn:btih:" + INFOHASH + "&dn=Some.Movie.2024"));

        assertThat(augmented.getDisplayName()).contains("Some.Movie.2024");
    }

    @Test
    @DisplayName("an empty tracker list hands the magnet back untouched")
    void emptyListIsANoOp() {
        MagnetUri original = parse("magnet:?xt=urn:btih:" + INFOHASH);

        assertThat(new MagnetTrackerInjector(List.of(), List.of()).augment(original)).isSameAs(original);
    }

    @Test
    void ignoresBlankEntriesInTheConfiguredList() {
        MagnetUri augmented = new MagnetTrackerInjector(List.of("  ", "", "udp://real.example:80/announce"), List.of())
                .augment(parse("magnet:?xt=urn:btih:" + INFOHASH));

        assertThat(augmented.getTrackerUrls()).containsExactly("udp://real.example:80/announce");
    }

    @Test
    @DisplayName("strips announce hosts that have not existed for years")
    void removesDeadTrackers() {
        // Every one of these came out of a single real magnet. Each cost a full tracker timeout on
        // every announce round, serially, ahead of the live trackers queued behind them.
        MagnetUri augmented = new MagnetTrackerInjector(
                        List.of("udp://alive.example:1337/announce"),
                        List.of("public.popcorn-tracker.org", "tracker.coppersurfer.tk"))
                .augment(parse("magnet:?xt=urn:btih:" + INFOHASH
                        + "&tr=udp%3A%2F%2Fpublic.popcorn-tracker.org%3A6969%2Fannounce"
                        + "&tr=udp%3A%2F%2Ftracker.coppersurfer.tk%3A6969"
                        + "&tr=udp%3A%2F%2Fstill.here%3A80%2Fannounce"));

        assertThat(augmented.getTrackerUrls())
                .containsExactlyInAnyOrder("udp://still.here:80/announce", "udp://alive.example:1337/announce");
    }

    @Test
    @DisplayName("matches a dead tracker on its host, whatever port or path it was spelled with")
    void matchesDeadTrackersOnHostAlone() {
        // The same dead machine appears as udp://x:6969/announce, udp://x:1337 and http://x/announce
        // across indexers. Listing every spelling guarantees one gets missed.
        MagnetUri augmented = new MagnetTrackerInjector(List.of(), List.of("GloTorrents.pw"))
                .augment(parse("magnet:?xt=urn:btih:" + INFOHASH
                        + "&tr=udp%3A%2F%2Fglotorrents.pw%3A6969%2Fannounce"
                        + "&tr=http%3A%2F%2Fglotorrents.pw%2Fannounce"
                        + "&tr=udp%3A%2F%2Fglotorrents.pw"));

        assertThat(augmented.getTrackerUrls()).isEmpty();
    }
}
