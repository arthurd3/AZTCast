package com.azt.streaming.providers.infrastructure;

import com.azt.streaming.providers.domain.NetworkKind;
import com.azt.streaming.providers.domain.PeerLocation;
import com.azt.streaming.providers.domain.PeerRole;
import com.azt.streaming.providers.domain.ProviderPeer;
import com.azt.streaming.providers.domain.ProviderPeerRepository;
import com.azt.streaming.providers.domain.ProviderSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * The provider log, in one SQLite file.
 *
 * <p>One row per peer per video, upserted. A peer in a two-hour download is seen thousands of times
 * — every discovery, connection and bitfield update — and a row per event would turn a modest log
 * into a large one that answers the same questions worse. What is worth keeping is the peer: when
 * it first appeared, when it was last seen, how often it connected, and the best description of it
 * anyone managed to obtain.
 *
 * <p>{@code COALESCE} on the merge is what "best description" means in practice. The client name
 * arrives on a handshake and the piece counts arrive on a bitfield, so most sightings of a peer
 * carry neither; a plain overwrite would erase what an earlier one had learned.
 */
public class SqliteProviderPeerRepository implements ProviderPeerRepository {

    private static final String UPSERT =
            """
            INSERT INTO provider_peer (
                video_id, info_hash, ip_address, port, client,
                country_code, country, city, asn, network,
                latitude, longitude, accuracy_radius_km,
                role, pieces_complete, pieces_total, times_connected,
                bytes_downloaded, bytes_uploaded, connected_seconds, capabilities, first_seen, last_seen)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(video_id, ip_address, port) DO UPDATE SET
                client             = COALESCE(excluded.client, provider_peer.client),
                country_code       = COALESCE(excluded.country_code, provider_peer.country_code),
                country            = COALESCE(excluded.country, provider_peer.country),
                city               = COALESCE(excluded.city, provider_peer.city),
                asn                = COALESCE(excluded.asn, provider_peer.asn),
                network            = COALESCE(excluded.network, provider_peer.network),
                latitude           = COALESCE(excluded.latitude, provider_peer.latitude),
                longitude          = COALESCE(excluded.longitude, provider_peer.longitude),
                accuracy_radius_km = COALESCE(excluded.accuracy_radius_km, provider_peer.accuracy_radius_km),
                pieces_complete    = COALESCE(excluded.pieces_complete, provider_peer.pieces_complete),
                pieces_total       = COALESCE(excluded.pieces_total, provider_peer.pieces_total),
                -- Only a bitfield knows the role, so an event without one must not blank it.
                role               = CASE WHEN excluded.role = 'UNKNOWN' THEN provider_peer.role ELSE excluded.role END,
                times_connected    = provider_peer.times_connected + excluded.times_connected,
                -- MAX, not last-write-wins: a peer that reconnects restarts its counters from zero,
                -- and overwriting would throw away everything it served the first time round.
                bytes_downloaded   = MAX(provider_peer.bytes_downloaded, excluded.bytes_downloaded),
                bytes_uploaded     = MAX(provider_peer.bytes_uploaded, excluded.bytes_uploaded),
                -- MAX, like the byte counters and for the same reason: this is now sampled
                -- repeatedly while a connection is open, so each sighting reports the duration so
                -- far. Adding them up would count the same seconds over and over.
                connected_seconds  = MAX(provider_peer.connected_seconds, excluded.connected_seconds),
                capabilities       = COALESCE(excluded.capabilities, provider_peer.capabilities),
                last_seen          = MAX(provider_peer.last_seen, excluded.last_seen)
            """;

    private static final String SELECT =
            """
            SELECT video_id, info_hash, ip_address, port, client,
                   country_code, country, city, asn, network,
                   latitude, longitude, accuracy_radius_km,
                   role, pieces_complete, pieces_total, times_connected,
                   bytes_downloaded, bytes_uploaded, connected_seconds, capabilities,
                   first_seen, last_seen,
                   -- How many downloads this address turned up in. A peer that served three of
                   -- your videos is a different thing from one that served one, and swarm research
                   -- leans on exactly this kind of recurrence.
                   (SELECT COUNT(DISTINCT other.video_id)
                      FROM provider_peer other
                     WHERE other.ip_address = provider_peer.ip_address) AS videos_served
            FROM provider_peer
            """;

    private final JdbcTemplate jdbc;

    public SqliteProviderPeerRepository(JdbcTemplate providerJdbcTemplate) {
        this.jdbc = providerJdbcTemplate;
    }

    @Override
    public void record(ProviderPeer peer) {
        PeerLocation location = peer.location() == null ? PeerLocation.UNKNOWN : peer.location();
        jdbc.update(
                UPSERT,
                peer.videoId(),
                peer.infoHash(),
                peer.ipAddress(),
                peer.port(),
                peer.client(),
                location.countryCode(),
                location.country(),
                location.city(),
                location.asn(),
                location.network(),
                location.latitude(),
                location.longitude(),
                location.accuracyRadiusKm(),
                peer.role().name(),
                peer.piecesComplete(),
                peer.piecesTotal(),
                peer.timesConnected(),
                peer.bytesDownloaded(),
                peer.bytesUploaded(),
                peer.connectedSeconds(),
                peer.capabilities() == null || peer.capabilities().isEmpty()
                        ? null
                        : String.join(",", peer.capabilities()),
                peer.firstSeen().toEpochMilli(),
                peer.lastSeen().toEpochMilli());
    }

    @Override
    public List<ProviderPeer> findByVideoId(String videoId) {
        return jdbc.query(SELECT + " WHERE video_id = ? ORDER BY bytes_downloaded DESC, last_seen DESC", MAPPER, videoId);
    }

    @Override
    public List<ProviderPeer> findRecent(int limit) {
        return jdbc.query(SELECT + " ORDER BY last_seen DESC LIMIT ?", MAPPER, limit);
    }

    @Override
    public int deleteOlderThan(Instant cutoff) {
        return jdbc.update("DELETE FROM provider_peer WHERE last_seen < ?", cutoff.toEpochMilli());
    }

    @Override
    public ProviderSummary.Totals totals() {
        return jdbc.queryForObject(
                """
                SELECT COUNT(DISTINCT video_id)                        AS videos,
                       COUNT(*)                                        AS peers,
                       COALESCE(SUM(times_connected > 0), 0)           AS connected,
                       COUNT(DISTINCT country_code)                    AS countries,
                       COUNT(DISTINCT asn)                             AS networks,
                       COALESCE(SUM(bytes_downloaded), 0)              AS bytes_downloaded
                FROM provider_peer
                """,
                (rs, row) -> new ProviderSummary.Totals(
                        rs.getInt("videos"),
                        rs.getInt("peers"),
                        rs.getInt("connected"),
                        rs.getInt("countries"),
                        rs.getInt("networks"),
                        rs.getLong("bytes_downloaded"),
                        countHostingPeers(),
                        countRecurringPeers()));
    }

    /**
     * Peers on a network that looks like a datacenter or a VPN.
     *
     * <p>Counted in Java, not SQL, because the classification is derived rather than stored — which
     * is the point of deriving it. One row per distinct network keeps that cheap: a swarm of
     * hundreds of peers sits on a few dozen operators.
     */
    private int countHostingPeers() {
        return jdbc
                .query(
                        "SELECT asn, network, COUNT(*) AS peers FROM provider_peer GROUP BY asn, network",
                        (rs, row) -> HostingNetworks.classify(nullableLong(rs, "asn"), rs.getString("network"))
                                        == NetworkKind.HOSTING
                                ? rs.getInt("peers")
                                : 0)
                .stream()
                .mapToInt(Integer::intValue)
                .sum();
    }

    /** Addresses that showed up in more than one download. */
    private int countRecurringPeers() {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM (
                    SELECT ip_address FROM provider_peer
                    GROUP BY ip_address HAVING COUNT(DISTINCT video_id) > 1
                )
                """,
                Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public List<ProviderSummary.Slice> topValuesOf(Dimension dimension, int limit) {
        // The column comes from a closed enum, never from a caller — the only safe way to vary a
        // column name, since it cannot be bound as a parameter.
        String sql =
                """
                SELECT %s AS label,
                       COUNT(*) AS peers,
                       COALESCE(SUM(bytes_downloaded), 0) AS bytes_downloaded
                FROM provider_peer
                WHERE %s IS NOT NULL AND %s <> ''
                GROUP BY label
                ORDER BY peers DESC, bytes_downloaded DESC
                LIMIT ?
                """
                        .formatted(dimension.column(), dimension.column(), dimension.column());

        return jdbc.query(
                sql,
                (rs, row) -> new ProviderSummary.Slice(
                        rs.getString("label"), rs.getInt("peers"), rs.getLong("bytes_downloaded")),
                limit);
    }

    @Override
    public List<VideoAggregate> aggregateByVideo() {
        return jdbc.query(
                """
                SELECT video_id,
                       COUNT(*)                              AS peers,
                       COALESCE(SUM(times_connected > 0), 0) AS connected,
                       COALESCE(SUM(role = 'SEEDER'), 0)     AS seeders,
                       COALESCE(SUM(bytes_downloaded), 0)    AS bytes_downloaded,
                       MIN(first_seen)                       AS first_seen,
                       MAX(last_seen)                        AS last_seen
                FROM provider_peer
                GROUP BY video_id
                ORDER BY last_seen DESC
                """,
                (rs, row) -> new VideoAggregate(
                        rs.getString("video_id"),
                        rs.getInt("peers"),
                        rs.getInt("connected"),
                        rs.getInt("seeders"),
                        rs.getLong("bytes_downloaded"),
                        Instant.ofEpochMilli(rs.getLong("first_seen")),
                        Instant.ofEpochMilli(rs.getLong("last_seen"))));
    }

    /**
     * Places, grouped by coordinate rounded to two decimals — roughly a kilometre.
     *
     * <p>Rounded rather than exact because two addresses in one city can differ in the last decimal
     * while describing the same estimate, and drawing them apart would invent a distinction the data
     * does not make. Two decimals is already finer than any accuracy radius here.
     */
    @Override
    public List<ProviderSummary.Place> aggregateByPlace(String videoId) {
        String sql =
                """
                SELECT ROUND(latitude, 2)                    AS lat,
                       ROUND(longitude, 2)                   AS lon,
                       MAX(accuracy_radius_km)               AS accuracy_radius_km,
                       MIN(city)                             AS city,
                       MIN(country)                          AS country,
                       MIN(country_code)                     AS country_code,
                       COUNT(*)                              AS peers,
                       COALESCE(SUM(times_connected > 0), 0) AS connected,
                       COALESCE(SUM(bytes_downloaded), 0)    AS bytes_downloaded,
                       GROUP_CONCAT(DISTINCT network)        AS networks
                FROM provider_peer
                WHERE latitude IS NOT NULL AND longitude IS NOT NULL
                %s
                GROUP BY lat, lon
                ORDER BY bytes_downloaded DESC, peers DESC
                """
                        .formatted(videoId == null ? "" : "AND video_id = ?");

        RowMapper<ProviderSummary.Place> mapper = (rs, row) -> new ProviderSummary.Place(
                rs.getDouble("lat"),
                rs.getDouble("lon"),
                nullableInt(rs, "accuracy_radius_km"),
                rs.getString("city"),
                rs.getString("country"),
                rs.getString("country_code"),
                rs.getInt("peers"),
                rs.getInt("connected"),
                rs.getLong("bytes_downloaded"),
                splitNetworks(rs.getString("networks")));

        return videoId == null ? jdbc.query(sql, mapper) : jdbc.query(sql, mapper, videoId);
    }

    /** Splits a stored comma-separated list back into values. */
    private static List<String> splitList(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        return Arrays.stream(joined.split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
    }

    /** {@code GROUP_CONCAT} returns one comma-joined string, or null when every value was null. */
    private static List<String> splitNetworks(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        return Arrays.stream(joined.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .distinct()
                .limit(8)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static final RowMapper<ProviderPeer> MAPPER = (ResultSet rs, int rowNum) -> new ProviderPeer(
            rs.getString("video_id"),
            rs.getString("info_hash"),
            rs.getString("ip_address"),
            rs.getInt("port"),
            rs.getString("client"),
            new PeerLocation(
                    rs.getString("country_code"),
                    rs.getString("country"),
                    rs.getString("city"),
                    nullableLong(rs, "asn"),
                    rs.getString("network"),
                    nullableDouble(rs, "latitude"),
                    nullableDouble(rs, "longitude"),
                    nullableInt(rs, "accuracy_radius_km")),
            PeerRole.valueOf(rs.getString("role")),
            nullableInt(rs, "pieces_complete"),
            nullableInt(rs, "pieces_total"),
            rs.getInt("times_connected"),
            rs.getLong("bytes_downloaded"),
            rs.getLong("bytes_uploaded"),
            rs.getInt("connected_seconds"),
            splitList(rs.getString("capabilities")),
            // Derived here rather than stored: the classifier is a heuristic, and one that improves
            // should apply to every row already written, not only to rows written after it changed.
            HostingNetworks.classify(nullableLong(rs, "asn"), rs.getString("network")),
            rs.getInt("videos_served"),
            Instant.ofEpochMilli(rs.getLong("first_seen")),
            Instant.ofEpochMilli(rs.getLong("last_seen")));

    /** {@code getInt} reports 0 for SQL NULL, which is a real piece count. */
    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
