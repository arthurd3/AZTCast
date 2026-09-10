package com.azt.streaming.providers.infrastructure;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The provider log's table, and how it gets from any older shape to the current one.
 *
 * <p>Its own class so that the tests exercise the real schema instead of a copy. They used to
 * declare their own {@code CREATE TABLE}, which is the sort of duplication that works until the
 * moment a column is added — and then the tests keep passing against a table that no longer
 * resembles production.
 */
@Slf4j
public final class ProviderSchema {

    private ProviderSchema() {}

    /**
     * The table as first shipped.
     *
     * <p>Frozen deliberately. New columns go in {@link #ADDED_COLUMNS} and are applied by
     * {@code ALTER TABLE}, because {@code CREATE TABLE IF NOT EXISTS} does nothing to a table that
     * already exists — on every install but the first, the migration is the only part that runs.
     * Editing this statement would create a database that new installs have and old ones never get.
     */
    private static final String CREATE_TABLE =
            """
            CREATE TABLE IF NOT EXISTS provider_peer (
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

    /**
     * Columns added after the first release, in order.
     *
     * <p>Every one is nullable or defaulted, so an existing row stays valid and simply has less to
     * say — which is the truth about a peer seen before the field was collected. Nothing is
     * backfilled, because there is nothing to backfill it from.
     */
    private static final Map<String, String> ADDED_COLUMNS = new LinkedHashMap<>();

    static {
        ADDED_COLUMNS.put("latitude", "REAL");
        ADDED_COLUMNS.put("longitude", "REAL");
        ADDED_COLUMNS.put("accuracy_radius_km", "INTEGER");
        ADDED_COLUMNS.put("bytes_downloaded", "INTEGER NOT NULL DEFAULT 0");
        ADDED_COLUMNS.put("bytes_uploaded", "INTEGER NOT NULL DEFAULT 0");
        ADDED_COLUMNS.put("connected_seconds", "INTEGER NOT NULL DEFAULT 0");
        // Comma-separated rather than a column per flag: a new extension is then a value, not a
        // migration, and nothing here ever queries for one flag on its own.
        ADDED_COLUMNS.put("capabilities", "TEXT");
    }

    /** Creates the table if it is absent, then brings whatever is there up to date. */
    public static void apply(JdbcTemplate jdbc) {
        jdbc.execute(CREATE_TABLE);
        addMissingColumns(jdbc);
        // Both read paths order by last_seen, and the reaper deletes by it.
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_provider_peer_last_seen ON provider_peer(last_seen)");
        // The map groups on these and skips rows without them.
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_provider_peer_place ON provider_peer(latitude, longitude)");
    }

    private static void addMissingColumns(JdbcTemplate jdbc) {
        Set<String> existing =
                new HashSet<>(jdbc.query("PRAGMA table_info(provider_peer)", (rs, row) -> rs.getString("name")));

        ADDED_COLUMNS.forEach((column, definition) -> {
            if (existing.contains(column)) {
                return;
            }
            jdbc.execute("ALTER TABLE provider_peer ADD COLUMN " + column + " " + definition);
            log.info("Provider log: added column {}", column);
        });
    }
}
