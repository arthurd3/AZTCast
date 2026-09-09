package com.azt.streaming.providers.infrastructure;

import com.azt.streaming.acquisition.domain.PeerObservationSink;
import com.azt.streaming.providers.application.ProviderPeerLog;
import com.azt.streaming.providers.domain.ProviderPeerRepository;
import com.azt.streaming.shared.config.StreamingProperties;
import com.maxmind.geoip2.DatabaseReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Wiring for the provider log.
 *
 * <p>Everything here is gated on {@code aztcast.streaming.providers.enabled}. With it off, no
 * database file is created, no {@code .mmdb} is opened and the no-op sink in the acquisition slice
 * stays bound — the feature is genuinely absent rather than present and idle, which matters for one
 * that retains addresses.
 *
 * <p>The DataSource is built by hand rather than left to Boot's autoconfiguration. Boot would make
 * this the application's DataSource, and there isn't one: nothing else here uses SQL, and a
 * `spring.datasource` that appears because a feature flag was set is a surprise waiting for whoever
 * adds the second database.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "aztcast.streaming.providers", name = "enabled", havingValue = "true")
public class ProvidersConfiguration {

    @Bean
    public DataSource providerDataSource(StreamingProperties properties) throws IOException {
        Path database = properties.providers().databasePath().toAbsolutePath();
        Files.createDirectories(database.getParent());

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + database);
        log.info("Provider log: {}", database);
        return dataSource;
    }

    @Bean
    public JdbcTemplate providerJdbcTemplate(DataSource providerDataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(providerDataSource);
        // WAL so a reader (the API answering a request) never blocks the writer thread draining the
        // queue. NORMAL because losing the last few sightings to a power cut is not worth an fsync
        // per peer.
        jdbc.execute("PRAGMA journal_mode=WAL");
        jdbc.execute("PRAGMA synchronous=NORMAL");
        ProviderSchema.apply(jdbc);
        return jdbc;
    }


    @Bean
    public ProviderPeerRepository providerPeerRepository(JdbcTemplate providerJdbcTemplate) {
        return new SqliteProviderPeerRepository(providerJdbcTemplate);
    }

    @Bean(destroyMethod = "close")
    public GeoIpEnricher geoIpEnricher(StreamingProperties properties) {
        StreamingProperties.Providers config = properties.providers();
        return new GeoIpEnricher(
                openDatabase(config.geoipCityDatabase(), "city"), openDatabase(config.geoipAsnDatabase(), "ASN"));
    }

    /**
     * Opens an {@code .mmdb}, or returns null.
     *
     * <p>A missing or unreadable database is a warning, never a startup failure. These files are not
     * bundled — MaxMind's need an account, DB-IP's and IP2Location's do not — so the common case is
     * that there is no file, and refusing to boot over an optional decoration would be absurd.
     */
    private static DatabaseReader openDatabase(String configured, String kind) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        File file = new File(configured.trim());
        if (!file.isFile()) {
            log.warn("GeoIP {} database not found at {}; peers will be recorded without it", kind, file);
            return null;
        }
        try {
            DatabaseReader reader = new DatabaseReader.Builder(file).build();
            log.info("GeoIP {} database: {}", kind, file);
            return reader;
        } catch (IOException e) {
            log.warn("Cannot read the GeoIP {} database at {}; peers will be recorded without it", kind, file, e);
            return null;
        }
    }

    /**
     * The sink the acquisition slice reports into.
     *
     * <p>Published as {@link PeerObservationSink} so acquisition binds to this instead of the no-op,
     * without knowing anything about SQLite, geolocation, or that a provider log exists at all.
     */
    @Bean
    public ProviderPeerLog providerPeerLog(
            ProviderPeerRepository providerPeerRepository,
            GeoIpEnricher geoIpEnricher,
            com.azt.streaming.shared.storage.VideoCatalog videoCatalog,
            Clock clock,
            StreamingProperties properties) {
        StreamingProperties.Providers config = properties.providers();
        return new ProviderPeerLog(
                providerPeerRepository,
                geoIpEnricher,
                videoCatalog,
                clock,
                config.retention(),
                config.queueCapacity());
    }
}
