package com.azt.streaming.providers.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Upgrading an existing provider log.
 *
 * <p>This is the one change here with a real upgrade path, and the one whose failure mode is quiet:
 * {@code CREATE TABLE IF NOT EXISTS} succeeds against a table missing half its columns and reports
 * nothing, so a broken migration looks exactly like a working one until the first query.
 *
 * <p>The old schema is written out literally below rather than taken from {@link ProviderSchema}.
 * That is deliberate and is not the duplication the repository test removed: this fixture is a
 * record of a shape that shipped, and it has to stay fixed even as the current schema moves.
 */
class ProviderSchemaTest {

    /** provider_peer exactly as it shipped, before coordinates and byte counters existed. */
    private static final String ORIGINAL_SCHEMA =
            """
            CREATE TABLE provider_peer (
                video_id        TEXT    NOT NULL,
                info_hash       TEXT    NOT NULL,
                ip_address      TEXT    NOT NULL,
                port            INTEGER NOT NULL,
                client          TEXT,
                country_code    TEXT,
                country         TEXT,
                city            TEXT,
                asn             INTEGER,
                network         TEXT,
                role            TEXT    NOT NULL,
                pieces_complete INTEGER,
                pieces_total    INTEGER,
                times_connected INTEGER NOT NULL DEFAULT 0,
                first_seen      INTEGER NOT NULL,
                last_seen       INTEGER NOT NULL,
                PRIMARY KEY (video_id, ip_address, port)
            )
            """;

    private static JdbcTemplate connect(Path database) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + database);
        return new JdbcTemplate(dataSource);
    }

    private static List<String> columnsOf(JdbcTemplate jdbc) {
        return jdbc.query("PRAGMA table_info(provider_peer)", (rs, row) -> rs.getString("name"));
    }

    @Test
    @DisplayName("an existing table gains the new columns and keeps its rows")
    void migratesInPlace(@TempDir Path tempDir) {
        JdbcTemplate jdbc = connect(tempDir.resolve("providers.db"));
        jdbc.execute(ORIGINAL_SCHEMA);
        jdbc.update(
                """
                INSERT INTO provider_peer
                    (video_id, info_hash, ip_address, port, client, role, times_connected, first_seen, last_seen)
                VALUES ('v1','hash','203.0.113.7',51413,'qBittorrent 4.6.5','SEEDER',3,1000,2000)
                """);

        ProviderSchema.apply(jdbc);

        assertThat(columnsOf(jdbc))
                .contains(
                        "latitude",
                        "longitude",
                        "accuracy_radius_km",
                        "bytes_downloaded",
                        "bytes_uploaded",
                        "connected_seconds",
                        "capabilities");

        // The row survives, keeps what it knew, and defaults what it could not have known.
        jdbc.query("SELECT client, times_connected, bytes_downloaded, latitude FROM provider_peer", rs -> {
            assertThat(rs.getString("client")).isEqualTo("qBittorrent 4.6.5");
            assertThat(rs.getInt("times_connected")).isEqualTo(3);
            assertThat(rs.getLong("bytes_downloaded")).as("defaulted, not null").isZero();
            rs.getDouble("latitude");
            assertThat(rs.wasNull()).as("nothing to backfill a coordinate from").isTrue();
        });
    }

    @Test
    @DisplayName("running it twice changes nothing the second time")
    void isIdempotent(@TempDir Path tempDir) {
        // It runs on every start, not once, so re-entrancy is the normal case rather than an edge.
        JdbcTemplate jdbc = connect(tempDir.resolve("providers.db"));

        ProviderSchema.apply(jdbc);
        List<String> afterFirst = columnsOf(jdbc);
        ProviderSchema.apply(jdbc);

        assertThat(columnsOf(jdbc)).isEqualTo(afterFirst);
    }

    @Test
    void createsTheTableFromNothing(@TempDir Path tempDir) {
        JdbcTemplate jdbc = connect(tempDir.resolve("providers.db"));

        ProviderSchema.apply(jdbc);

        assertThat(columnsOf(jdbc)).contains("video_id", "latitude", "bytes_downloaded");
    }
}
