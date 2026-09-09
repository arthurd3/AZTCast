package com.azt.streaming.providers.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.azt.streaming.providers.domain.NetworkKind;
import com.azt.streaming.providers.domain.PeerLocation;
import com.azt.streaming.providers.domain.PeerRole;
import com.azt.streaming.providers.domain.ProviderPeer;
import com.azt.streaming.providers.domain.ProviderPeerRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The merge is the whole design, so it is what these test.
 *
 * <p>A peer is seen thousands of times in one download and almost every sighting knows less than
 * the last: a discovery has no client name, a connection has no piece counts. The row has to
 * accumulate rather than be overwritten, or the record ends up describing the most recent packet
 * instead of the peer.
 *
 * <p>The schema comes from {@link ProviderSchema}, the same code production runs. These tests used
 * to declare their own copy of the DDL, which passed happily against a table that had stopped
 * resembling the real one.
 */
class SqliteProviderPeerRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-09-08T12:00:00Z");
    private static final PeerLocation SAO_PAULO =
            new PeerLocation("BR", "Brazil", "São Paulo", 28573L, "Claro NXT", -23.55, -46.63, 50);

    private SqliteProviderPeerRepository repository;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        repository = new SqliteProviderPeerRepository(schema(tempDir.resolve("providers.db")));
    }

    static JdbcTemplate schema(Path database) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + database);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ProviderSchema.apply(jdbc);
        return jdbc;
    }

    private static ProviderPeer peer(
            String client, PeerLocation location, PeerRole role, Integer complete, int connected, long bytes, Instant at) {
        return new ProviderPeer(
                "v1", "hash", "203.0.113.7", 51413, client, location, role, complete,
                complete == null ? null : 100, connected, bytes, 0L, 0, List.of(), null, 0, at, at);
    }

    @Test
    @DisplayName("a second sighting merges into the first rather than replacing it")
    void mergesRepeatedSightings() {
        repository.record(peer(null, PeerLocation.UNKNOWN, PeerRole.UNKNOWN, null, 0, 0, T0));
        repository.record(peer("qBittorrent 4.6.5", PeerLocation.UNKNOWN, PeerRole.UNKNOWN, null, 1, 0, T0.plusSeconds(5)));
        repository.record(peer(null, PeerLocation.UNKNOWN, PeerRole.SEEDER, 100, 0, 0, T0.plusSeconds(9)));

        assertThat(repository.findByVideoId("v1")).singleElement().satisfies(stored -> {
            // Learned on the second sighting, and not erased by the third, which did not know it.
            assertThat(stored.client()).isEqualTo("qBittorrent 4.6.5");
            assertThat(stored.role()).isEqualTo(PeerRole.SEEDER);
            assertThat(stored.piecesComplete()).isEqualTo(100);
            assertThat(stored.timesConnected()).as("only the connection counts").isEqualTo(1);
            assertThat(stored.firstSeen()).isEqualTo(T0);
            assertThat(stored.lastSeen()).isEqualTo(T0.plusSeconds(9));
        });
    }

    @Test
    @DisplayName("a reconnecting peer does not lose the bytes it already served")
    void keepsTheHighestByteCount() {
        // Counters live on the connection, so a peer that drops and comes back starts again at zero.
        // Last-write-wins would quietly erase everything it sent the first time round.
        repository.record(peer(null, PeerLocation.UNKNOWN, PeerRole.UNKNOWN, null, 1, 340_000_000L, T0));
        repository.record(peer(null, PeerLocation.UNKNOWN, PeerRole.UNKNOWN, null, 1, 12_000L, T0.plusSeconds(60)));

        assertThat(repository.findByVideoId("v1"))
                .singleElement()
                .extracting(ProviderPeer::bytesDownloaded)
                .isEqualTo(340_000_000L);
    }

    @Test
    @DisplayName("connection time takes the longest sighting, not the sum of them")
    void keepsTheLongestConnection() {
        // Duration is sampled repeatedly while a connection is open, so each sighting reports the
        // time so far. Adding them would count the same seconds again on every sample.
        repository.record(withSeconds(30, T0));
        repository.record(withSeconds(90, T0.plusSeconds(60)));
        repository.record(withSeconds(60, T0.plusSeconds(120)));

        assertThat(repository.findByVideoId("v1"))
                .singleElement()
                .extracting(ProviderPeer::connectedSeconds)
                .isEqualTo(90);
    }

    private static ProviderPeer withSeconds(int seconds, Instant at) {
        return new ProviderPeer(
                "v1", "hash", "203.0.113.7", 51413, null, PeerLocation.UNKNOWN, PeerRole.UNKNOWN, null, null, 0, 0L,
                0L, seconds, List.of(), null, 0, at, at);
    }

    @Test
    @DisplayName("a later sighting that knows nothing does not blank the role")
    void keepsTheRoleWhenAnEventCannotKnowIt() {
        repository.record(peer(null, PeerLocation.UNKNOWN, PeerRole.SEEDER, 100, 0, 0, T0));
        repository.record(peer(null, PeerLocation.UNKNOWN, PeerRole.UNKNOWN, null, 0, 0, T0.plusSeconds(1)));

        assertThat(repository.findByVideoId("v1")).singleElement().extracting(ProviderPeer::role).isEqualTo(PeerRole.SEEDER);
    }

    @Test
    void keepsLocationOnceLearned() {
        repository.record(peer(null, SAO_PAULO, PeerRole.UNKNOWN, null, 0, 0, T0));
        repository.record(peer(null, PeerLocation.UNKNOWN, PeerRole.UNKNOWN, null, 0, 0, T0.plusSeconds(1)));

        assertThat(repository.findByVideoId("v1")).singleElement().extracting(ProviderPeer::location).isEqualTo(SAO_PAULO);
    }

    @Test
    void separatesPeersByPortAndVideo() {
        repository.record(peer(null, PeerLocation.UNKNOWN, PeerRole.UNKNOWN, null, 0, 0, T0));
        repository.record(new ProviderPeer(
                "v1", "hash", "203.0.113.7", 6881, null, PeerLocation.UNKNOWN, PeerRole.UNKNOWN, null, null, 0, 0L, 0L, 0, List.of(), null, 0, T0, T0));
        repository.record(new ProviderPeer(
                "v2", "hash", "203.0.113.7", 51413, null, PeerLocation.UNKNOWN, PeerRole.UNKNOWN, null, null, 0, 0L, 0L, 0, List.of(), null, 0, T0, T0));

        assertThat(repository.findByVideoId("v1")).hasSize(2);
        assertThat(repository.findByVideoId("v2")).hasSize(1);
        assertThat(repository.findRecent(10)).hasSize(3);
    }

    @Test
    void expiresRowsPastTheirWindow() {
        repository.record(peer(null, PeerLocation.UNKNOWN, PeerRole.UNKNOWN, null, 0, 0, T0));
        repository.record(new ProviderPeer(
                "v1", "hash", "198.51.100.9", 51413, null, PeerLocation.UNKNOWN, PeerRole.UNKNOWN, null, null, 0, 0L, 0L, 0,
                List.of(), null, 0, T0.plusSeconds(600), T0.plusSeconds(600)));

        assertThat(repository.deleteOlderThan(T0.plusSeconds(300))).isEqualTo(1);
        assertThat(repository.findByVideoId("v1")).singleElement().extracting(ProviderPeer::ipAddress).isEqualTo("198.51.100.9");
    }

    // ---------------------------------------------------------------- aggregation, for the map

    @Test
    @DisplayName("peers at one coordinate become one place, not one dot each")
    void groupsPlacesByCoordinate() {
        // Two addresses, one city centroid — the normal case, because a free database resolves a
        // whole city to a single point. Drawn separately they would stack invisibly and imply two
        // locations where the data claims one.
        repository.record(peer(null, SAO_PAULO, PeerRole.SEEDER, 100, 1, 1_000L, T0));
        repository.record(new ProviderPeer(
                "v1", "hash", "203.0.113.8", 51413, null, SAO_PAULO, PeerRole.SEEDER, 100, 100, 1, 500L, 0L, 0, List.of(), null, 0, T0, T0));

        assertThat(repository.aggregateByPlace(null)).singleElement().satisfies(place -> {
            assertThat(place.peerCount()).isEqualTo(2);
            assertThat(place.connectedCount()).isEqualTo(2);
            assertThat(place.bytesDownloaded()).isEqualTo(1_500L);
            assertThat(place.city()).isEqualTo("São Paulo");
            assertThat(place.accuracyRadiusKm()).as("drawn, so the estimate is not read as a point").isEqualTo(50);
            assertThat(place.networks()).containsExactly("Claro NXT");
        });
    }

    @Test
    void leavesOutPeersWithNoCoordinates() {
        repository.record(peer(null, PeerLocation.UNKNOWN, PeerRole.UNKNOWN, null, 0, 0, T0));

        assertThat(repository.aggregateByPlace(null)).isEmpty();
        assertThat(repository.totals().peers()).as("still counted, just not mappable").isEqualTo(1);
    }

    @Test
    void narrowsPlacesToOneVideo() {
        repository.record(peer(null, SAO_PAULO, PeerRole.UNKNOWN, null, 0, 0, T0));
        repository.record(new ProviderPeer(
                "v2", "hash", "198.51.100.4", 6881, null,
                new PeerLocation("JP", "Japan", "Tokyo", 2497L, "IIJ", 35.68, 139.76, 20),
                PeerRole.UNKNOWN, null, null, 0, 0L, 0L, 0, List.of(), null, 0, T0, T0));

        assertThat(repository.aggregateByPlace("v1")).singleElement().extracting(p -> p.country()).isEqualTo("Brazil");
        assertThat(repository.aggregateByPlace(null)).hasSize(2);
    }

    @Test
    @DisplayName("an address seen in two downloads is counted as one recurring peer")
    void countsPeersThatServedMoreThanOneVideo() {
        // Same address, two videos. This is the pattern swarm research leans on: a peer that keeps
        // turning up is a different kind of fact from one seen once.
        repository.record(peer(null, SAO_PAULO, PeerRole.UNKNOWN, null, 1, 0, T0));
        repository.record(new ProviderPeer(
                "v2", "hash", "203.0.113.7", 51413, null, SAO_PAULO, PeerRole.UNKNOWN, null, null, 1, 0L, 0L, 0,
                List.of(), null, 0, T0, T0));
        // A different address in one video only, which must not be counted.
        repository.record(new ProviderPeer(
                "v1", "hash", "198.51.100.9", 6881, null, SAO_PAULO, PeerRole.UNKNOWN, null, null, 0, 0L, 0L, 0,
                List.of(), null, 0, T0, T0));

        assertThat(repository.totals().recurringPeers()).isEqualTo(1);
        assertThat(repository.findByVideoId("v1"))
                .filteredOn(p -> p.ipAddress().equals("203.0.113.7"))
                .singleElement()
                .extracting(ProviderPeer::videosServed)
                .isEqualTo(2);
    }

    @Test
    @DisplayName("the network kind is derived on read, not stored")
    void classifiesTheNetworkWithoutStoringIt() {
        // Written with a datacenter operator; nothing about hosting is persisted, so the answer has
        // to come from the classifier at read time — which is what lets it improve retroactively.
        PeerLocation datacenter = new PeerLocation("NL", "Netherlands", "Amsterdam", 49453L, "Global Layer B.V.", 52.37, 4.9, null);
        repository.record(new ProviderPeer(
                "v1", "hash", "203.0.113.9", 6881, null, datacenter, PeerRole.UNKNOWN, null, null, 0, 0L, 0L, 0,
                List.of(), null, 0, T0, T0));

        assertThat(repository.findByVideoId("v1")).singleElement().satisfies(stored -> {
            assertThat(stored.networkKind()).isEqualTo(com.azt.streaming.providers.domain.NetworkKind.HOSTING);
        });
        assertThat(repository.totals().hostingPeers()).isEqualTo(1);
    }

    @Test
    void roundTripsCapabilities() {
        repository.record(new ProviderPeer(
                "v1", "hash", "203.0.113.7", 51413, null, PeerLocation.UNKNOWN, PeerRole.UNKNOWN, null, null, 0, 0L, 0L, 0,
                List.of("DHT", "EXT", "PEX"), null, 0, T0, T0));

        assertThat(repository.findByVideoId("v1"))
                .singleElement()
                .extracting(ProviderPeer::capabilities)
                .isEqualTo(List.of("DHT", "EXT", "PEX"));
    }

    @Test
    @DisplayName("a sighting that learned no capabilities does not erase the ones already known")
    void keepsCapabilitiesOnceLearned() {
        repository.record(new ProviderPeer(
                "v1", "hash", "203.0.113.7", 51413, null, PeerLocation.UNKNOWN, PeerRole.UNKNOWN, null, null, 0, 0L, 0L, 0,
                List.of("DHT"), null, 0, T0, T0));
        repository.record(peer(null, PeerLocation.UNKNOWN, PeerRole.UNKNOWN, null, 0, 0, T0.plusSeconds(5)));

        assertThat(repository.findByVideoId("v1"))
                .singleElement()
                .extracting(ProviderPeer::capabilities)
                .isEqualTo(List.of("DHT"));
    }

    @Test
    void ranksTheCommonestClientsCountriesAndNetworks() {
        repository.record(peer("qBittorrent 5.2.3", SAO_PAULO, PeerRole.UNKNOWN, null, 0, 100, T0));
        repository.record(new ProviderPeer(
                "v1", "hash", "203.0.113.8", 6881, "qBittorrent 5.2.3", SAO_PAULO, PeerRole.UNKNOWN, null, null, 0, 50L,
                0L, 0, List.of(), null, 0, T0, T0));
        repository.record(new ProviderPeer(
                "v1", "hash", "198.51.100.4", 6881, "Transmission 4.0.6",
                new PeerLocation("JP", "Japan", "Tokyo", 2497L, "IIJ", 35.68, 139.76, null),
                PeerRole.UNKNOWN, null, null, 0, 0L, 0L, 0, List.of(), null, 0, T0, T0));

        assertThat(repository.topValuesOf(ProviderPeerRepository.Dimension.CLIENT, 8))
                .extracting(s -> s.label(), s -> s.peers())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("qBittorrent 5.2.3", 2),
                        org.assertj.core.groups.Tuple.tuple("Transmission 4.0.6", 1));
        assertThat(repository.topValuesOf(ProviderPeerRepository.Dimension.COUNTRY, 8))
                .first()
                .extracting(s -> s.label())
                .isEqualTo("Brazil");
        assertThat(repository.topValuesOf(ProviderPeerRepository.Dimension.NETWORK, 1)).hasSize(1);
    }

    @Test
    void countsPerVideoAndOverall() {
        repository.record(peer(null, SAO_PAULO, PeerRole.SEEDER, 100, 1, 2_000L, T0));
        repository.record(new ProviderPeer(
                "v2", "hash", "198.51.100.4", 6881, null,
                new PeerLocation("JP", "Japan", "Tokyo", 2497L, "IIJ", 35.68, 139.76, 20),
                PeerRole.LEECHER, 3, 100, 0, 0L, 0L, 0, List.of(), null, 0, T0, T0));

        assertThat(repository.totals()).satisfies(totals -> {
            assertThat(totals.videos()).isEqualTo(2);
            assertThat(totals.peers()).isEqualTo(2);
            assertThat(totals.connected()).isEqualTo(1);
            assertThat(totals.countries()).isEqualTo(2);
            assertThat(totals.networks()).isEqualTo(2);
            assertThat(totals.bytesDownloaded()).isEqualTo(2_000L);
        });
        assertThat(repository.aggregateByVideo())
                .extracting(a -> a.videoId(), a -> a.peerCount(), a -> a.seederCount())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("v1", 1, 1),
                        org.assertj.core.groups.Tuple.tuple("v2", 1, 0));
    }
}
