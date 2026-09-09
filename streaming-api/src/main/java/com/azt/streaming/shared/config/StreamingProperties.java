package com.azt.streaming.shared.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;

/**
 * Every tunable the service has, bound and validated once at startup.
 *
 * <p>Before this existed, configuration was three scattered {@code @Value} fields plus hardcoded
 * literals: the ffmpeg binary name, the encoding ladder (three parallel {@code String[]}), the
 * thread-pool sizes and the CORS origins were all compiled in. Binding failures now surface as a
 * startup error rather than as a mystery {@code RuntimeException} half an hour into a torrent.
 */
@Validated
@ConfigurationProperties(prefix = "aztcast.streaming")
public record StreamingProperties(
        @NestedConfigurationProperty @Valid @NotNull Storage storage,
        @NestedConfigurationProperty @Valid @NotNull Ffmpeg ffmpeg,
        @NestedConfigurationProperty @Valid @NotNull Torrent torrent,
        @NestedConfigurationProperty @Valid @NotNull Transcoding transcoding,
        @NestedConfigurationProperty @Valid @NotNull Playback playback,
        @NestedConfigurationProperty @Valid @NotNull Redis redis,
        @NestedConfigurationProperty @Valid @NotNull Providers providers,
        @NestedConfigurationProperty @Valid @NotNull Web web) {

    /**
     * Where media lives on disk. The two directories used to sit under unrelated top-level paths
     * ({@code ./download-torrents/} and {@code ./downloads/hls}); they are siblings now so a single
     * ignore rule, a single Docker volume and a single {@code du} target cover both.
     *
     * <p>{@code retention} is how long media survives after its last modification. It should match
     * {@code redis.job-ttl}: a job that outlives its media reports READY for a video that is gone,
     * and media that outlives its job is a directory nothing refers to any more.
     */
    public record Storage(@NotNull Path downloadsDir, @NotNull Path hlsDir, @NotNull Duration retention) {}

    /**
     * The external ffmpeg process and the HLS ladder it produces.
     *
     * <p>{@code videoCodec} is configurable because {@code libx264} is not universally present:
     * distributions that ship a patent-free build (Fedora's default, for one) carry
     * {@code libopenh264} instead. Both produce H.264 Main@3.1, so the CODECS attribute the master
     * playlist advertises stays correct either way — but a hardcoded encoder name fails at runtime
     * on any host that lacks it.
     */
    public record Ffmpeg(
            @NotBlank String binary,
            @NotBlank String probeBinary,
            @NotBlank String videoCodec,
            @NotNull Duration timeout,
            @NotNull Duration segmentDuration,
            @NotEmpty List<@Valid Rendition> renditions) {}

    /** One rung of the encoding ladder. Replaces three index-aligned {@code String[]}. */
    public record Rendition(
            @NotBlank String name,
            @Positive int width,
            @Positive int height,
            @Positive int videoBitrateKbps,
            @Positive int audioBitrateKbps) {}

    /** BitTorrent acquisition limits. */
    public record Torrent(
            @NotEmpty List<String> videoExtensions,
            @NotNull Duration downloadTimeout,
            @NotNull Duration progressLogInterval,
            @NestedConfigurationProperty @Valid @NotNull Network network) {}

    /**
     * How the BitTorrent client presents itself on the network.
     *
     * <p>None of this makes the client anonymous, and it is worth being exact about why. BitTorrent
     * announces your address to the tracker and then connects directly to every peer in the swarm;
     * that is the protocol, not a leak. The library has no SOCKS support and cannot be given any —
     * it dials raw NIO channels, which ignore the JVM's proxy properties, and DHT is UDP through a
     * separate stack. Hiding the origin address is therefore a job for the network the process runs
     * in (a tunnel, a namespace), not for a setting here.
     *
     * <p>What these settings <em>do</em> is stop the client volunteering more than it has to.
     *
     * @param encryption message-stream encryption. {@code PREFER_ENCRYPTED} is the default rather
     *     than {@code REQUIRE_ENCRYPTED}: obfuscation defeats naive traffic classification, but
     *     requiring it drops every peer that will not do it, which on a thin swarm can mean all of
     *     them. It is not confidentiality against anyone watching the swarm itself.
     * @param disableLocalServiceDiscovery stop announcing to the local network by multicast. On by
     *     default: LSD tells every host on the LAN what this machine is downloading, which is
     *     rarely what a server on a shared network wants, and it finds peers only on that LAN.
     * @param disablePeerExchange stop trading peer lists with connected peers. Off by default —
     *     PEX is how a swarm stays healthy without leaning on trackers, and disabling it costs
     *     download speed to hide from peers who, being in the swarm, already see this client.
     * @param acceptorAddress the local address to bind to, or blank for whatever the library picks.
     *     Set this to a tunnel's address to keep torrent traffic off every other interface — the
     *     one knob here that genuinely affects which address peers see, because the library binds
     *     outgoing connections to it as well as the listening socket.
     * @param acceptorPort the TCP port for incoming peer connections.
     * @param maxPeerConnectionsPerTorrent ceiling on simultaneous peers for one torrent.
     */
    public record Network(
            @NotNull Encryption encryption,
            boolean disableLocalServiceDiscovery,
            boolean disablePeerExchange,
            String acceptorAddress,
            @Positive int acceptorPort,
            @Positive int maxPeerConnectionsPerTorrent) {}

    /**
     * Message-stream encryption policy, named to match the library's four values.
     *
     * <p>Declared here rather than reusing {@code bt.protocol.crypto.EncryptionPolicy} so that
     * {@code shared.config} stays free of the BitTorrent driver, for the same reason the Redis
     * driver is confined to its adapters: a configuration record is read by the whole application,
     * and it should not drag a slice's dependency along with it. The acquisition adapter maps these
     * names onto the library's enum, and a fitness function keeps {@code bt..} on its side of that
     * line.
     */
    public enum Encryption {
        REQUIRE_PLAINTEXT,
        PREFER_PLAINTEXT,
        PREFER_ENCRYPTED,
        REQUIRE_ENCRYPTED
    }

    /**
     * The provider log: which peers served which video.
     *
     * <p>Its own SQLite file rather than Redis or a sidecar JSON. Redis is optional here, expires
     * everything under a TTL and in the reference deployment runs with persistence off, so a record
     * meant to answer "who served this, and when" would not survive the week; a file beside the
     * media is deleted with the media. SQLite is the smallest thing that is genuinely queryable
     * across videos, and it adds no service to operate — which is the same reasoning ADR-0003 used
     * for putting media on the filesystem.
     *
     * @param enabled off by default. It records addresses, and a feature that quietly starts
     *     retaining personal data because it shipped is not a feature anyone consented to.
     * @param databasePath the SQLite file. Under the data directory, so one volume covers it.
     * @param geoipCityDatabase path to a City {@code .mmdb}, or blank to record no location. Not
     *     bundled and not downloaded: MaxMind's GeoLite2 needs an account and a licence key, and
     *     DB-IP and IP2Location publish compatible files that do not. Any of them works.
     * @param geoipAsnDatabase path to an ASN {@code .mmdb}, or blank. Separate file from the city
     *     one, and the more useful of the two — the network operator is a routing fact rather than
     *     a guess at a location.
     * @param retention how long a peer row outlives its last sighting. Addresses are personal data
     *     under the LGPD; keeping them forever because a database makes it easy is not a decision
     *     anyone made deliberately, so it is one made here.
     * @param queueCapacity how many unwritten sightings to hold. The swarm reports from many
     *     threads and SQLite writes from one, so there is a queue; past this it drops sightings
     *     rather than growing, because a download must not stall to log what it is downloading from.
     */
    public record Providers(
            boolean enabled,
            @NotNull Path databasePath,
            String geoipCityDatabase,
            String geoipAsnDatabase,
            @NotNull Duration retention,
            @Positive int queueCapacity) {}

    /** Executor backing {@code @Async} transcoding work. */
    public record Transcoding(@NestedConfigurationProperty @Valid @NotNull Pool pool) {

        public record Pool(
                @Positive int coreSize,
                @Positive int maxSize,
                @Positive int queueCapacity,
                @NotBlank String threadNamePrefix) {}
    }

    /**
     * How playback answers a request for bytes.
     *
     * <p>{@code offloadEnabled} swaps the whole delivery path. Off, this process writes segment
     * bytes itself. On, it returns headers only and nginx writes them with {@code sendfile} — which
     * requires an nginx in front, with the media volume mounted and a matching {@code internal}
     * location. The default is off because that is the configuration a bare
     * {@code mvn spring-boot:run} has, and a default that only works inside Docker is a default that
     * breaks development.
     *
     * <p>{@code internalPrefix} must match the {@code location} block nginx marks {@code internal};
     * they are two halves of one contract and there is no way for either side to check the other.
     */
    public record Playback(boolean offloadEnabled, @NotBlank String internalPrefix) {}

    /**
     * What Redis is used for, and what happens without it.
     *
     * <p>Scope is narrow on purpose. Redis holds <em>state and coordination</em> — job progress,
     * which magnets are already being ingested, and rate-limit counters. It holds no media: a value
     * over about a megabyte displaces thousands of useful keys, stalls the single-threaded event
     * loop for every other client, and slows replication, and a segment is far larger than that.
     * Segments live on disk and leave via nginx.
     *
     * <p>{@code enabled} defaults to false so the service still starts and works with no Redis
     * anywhere. Note what that costs and what it does not: without Redis, job state is per-instance
     * and lost on restart (as it always was), and duplicate ingestions are not detected. Playback is
     * completely unaffected either way — serving a segment never touches Redis.
     *
     * @param jobTtl how long a finished job stays queryable. Should not outlive the media itself,
     *     or a job reports READY for a video the reaper has already deleted.
     */
    public record Redis(
            boolean enabled,
            @NotNull Duration jobTtl,
            @NestedConfigurationProperty @Valid @NotNull RateLimit rateLimit) {

        /**
         * Token bucket for the ingestion endpoint.
         *
         * <p>This endpoint is unauthenticated and makes the server download arbitrary torrents, so
         * it is the one place where an absent limit is a real liability rather than a nicety.
         * Playback is deliberately not limited — a player pulls dozens of segments a minute, and
         * throttling that is throttling legitimate viewing.
         *
         * @param capacity burst size: how many ingestions one client may start back to back
         * @param refillPerHour sustained rate, spread smoothly rather than reset on the hour
         */
        public record RateLimit(@Positive int capacity, @Positive int refillPerHour) {}
    }

    /**
     * CORS origins. Empty is the correct production value: nginx reverse-proxies {@code /api} onto
     * the same origin that serves the player, so no cross-origin request is ever made.
     */
    public record Web(@NotNull List<String> allowedOrigins) {}
}
