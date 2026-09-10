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
     *
     * <p>Videos marked as kept in the library are exempt, and outlive both. See
     * {@link com.azt.streaming.shared.storage.MediaReaper} for why that asymmetry is safe.
     */
    public record Storage(@NotNull Path downloadsDir, @NotNull Path hlsDir, @NotNull Duration retention) {}

    /**
     * The external ffmpeg process and the HLS ladder it produces.
     *
     * <p>{@code videoCodec} accepts the sentinel {@code auto}, which is the shipped default and the
     * only setting that survives being moved between hosts. Naming an encoder outright pins the
     * service to one build: {@code libx264} is absent from every patent-free distribution, and the
     * previous answer to that was a hand-edited Spring profile saying "actually libopenh264 here" —
     * which covers exactly the machines someone remembered to write a profile for. Under
     * {@code auto} the first encoder from {@code videoCodecPreference} that the binary actually
     * carries is used, and a name is still accepted for anyone who wants to pin one.
     *
     * @param preset rate/quality preset handed to the encoder, or {@code auto} for a sensible
     *     default per encoder family. Emitted only when the resolved encoder accepts one —
     *     {@code libopenh264} has no presets, and passing it one fails the command.
     */
    public record Ffmpeg(
            @NotBlank String binary,
            @NotBlank String probeBinary,
            @NotBlank String videoCodec,
            @NotEmpty List<String> videoCodecPreference,
            @NotBlank String preset,
            @NotNull Duration timeout,
            @NotNull Duration segmentDuration,
            @NestedConfigurationProperty @Valid @NotNull Audio audio,
            @NestedConfigurationProperty @Valid @NotNull Subtitles subtitles,
            @NestedConfigurationProperty @Valid @NotNull Hardware hardware,
            @NotEmpty List<@Valid Rendition> renditions) {

        /** The sentinel that means "ask the binary what it has". */
        public static final String AUTO = "auto";

        public boolean autoVideoCodec() {
            return AUTO.equalsIgnoreCase(videoCodec);
        }

        public boolean autoPreset() {
            return AUTO.equalsIgnoreCase(preset);
        }
    }

    /**
     * The single audio rendition every rung shares.
     *
     * <p>Singular, since the ladder stopped encoding the same track once per rung. That cost five
     * AAC encodes and five copies of the same audio in the segments, and Apple's authoring
     * specification asks for the opposite.
     *
     * @param encoderPreference AAC encoders in descending order of preference; the first one this
     *     build carries is used
     * @param channels {@code source} to keep the layout the source had, or a number to force a
     *     downmix. {@code source} is the default and the reason is measured: a 5.1 track folded to
     *     stereo loses four channels, and Chrome reports {@code supported} and {@code smooth} for
     *     six-channel AAC — so preserving the layout costs nothing in reach and is the only version
     *     of this that deserves to be called high quality.
     * @param sampleRate {@code source} to keep the source's rate, or a number to resample. Forcing
     *     48 kHz put every 44.1 kHz source through a resampler for no benefit.
     * @param bitrateKbpsPerChannel scales the target with the layout — 64 gives 128k in stereo and
     *     384k at 5.1, which is what Apple's authoring specification asks for. A fixed number
     *     cannot be right for both.
     * @param maxBitrateKbps ceiling, so an exotic 7.1 or 9.1 source cannot ask for an absurd rate
     * @param onUndecodable what to do when the source's audio cannot be turned into AAC here —
     *     {@code passthrough}, {@code drop} or {@code fail}. The situation is ordinary rather than
     *     exotic: E-AC-3 is the audio of essentially every AMZN WEB-DL and a patent-free ffmpeg has
     *     no decoder for it, so this is the knob that decides whether such a file becomes a video
     *     with Apple-only sound, a silent video, or a failed ingestion.
     */
    public record Audio(
            @NotEmpty List<String> encoderPreference,
            @NotBlank String channels,
            @NotBlank String sampleRate,
            @Positive int bitrateKbpsPerChannel,
            @Positive int maxBitrateKbps,
            @NotNull UndecodableAudioPolicy onUndecodable) {

        /** The sentinel that means "whatever the source has", as {@code auto} does for the encoder. */
        public static final String SOURCE = "source";

        /** The configured channel count, or 0 for "match the source". */
        public int fixedChannels() {
            return numberOr(channels);
        }

        /** The configured sample rate, or 0 for "match the source". */
        public int fixedSampleRate() {
            return numberOr(sampleRate);
        }

        private static int numberOr(String value) {
            if (SOURCE.equalsIgnoreCase(value)) {
                return 0;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "aztcast.streaming.ffmpeg.audio expects a number or '%s', got '%s'".formatted(SOURCE, value));
            }
        }
    }

    /**
     * Text subtitle tracks in the source, republished as WebVTT.
     *
     * <p>Off is a legitimate setting: extraction spends one short pass per track, and an operator
     * serving a catalogue that never carries subtitles has no reason to pay it.
     */
    public record Subtitles(boolean enabled) {}

    /**
     * Hardware encoding.
     *
     * <p>Detection only for now — the resolved capability is reported on {@code /actuator/health}
     * and by the preflight script, and the encode path stays in software. {@code device} is the DRM
     * render node a VAAPI probe initialises.
     */
    public record Hardware(boolean enabled, String device) {}

    /**
     * One rung of the encoding ladder. Replaces three index-aligned {@code String[]}.
     *
     * <p>It carried an audio bitrate until the ladder moved to a shared audio rendition. A rung is a
     * video rendition now, and the one audio bitrate lives under {@code ffmpeg.audio}.
     */
    public record Rendition(
            @NotBlank String name,
            @Positive int width,
            @Positive int height,
            @Positive int videoBitrateKbps) {}

    /**
     * BitTorrent acquisition limits.
     *
     * @param extraTrackers announce URLs added to every magnet, on top of whatever it already
     *     carries. A magnet often arrives with no {@code &tr=} at all, or with trackers that died
     *     years ago, leaving DHT and PEX to find the whole swarm by themselves. The defaults are a
     *     snapshot of a public list rather than a live fetch: looking one up per ingestion would send
     *     a request naming what is about to be downloaded, which is the kind of leak ADR-0016
     *     already refuses elsewhere. Empty disables the whole mechanism.
     * @param deadTrackers announce hosts to strip out of a magnet before using it. Public magnets
     *     are copied from one indexer to the next for years and accumulate trackers that shut down
     *     long ago; each one still costs a full tracker timeout on every announce round, and the
     *     library queries them serially per source. Matched on host, so a port change does not
     *     resurrect one.
     * @param downloadVideoOnly skip every file in the torrent except the video that will actually be
     *     transcoded. A season pack is ten episodes of which this pipeline uses one, so the default
     *     behaviour spends nine tenths of the bandwidth on files it then deletes unread.
     */
    public record Torrent(
            @NotEmpty List<String> videoExtensions,
            @NotNull Duration downloadTimeout,
            @NotNull Duration progressLogInterval,
            @NotNull List<String> extraTrackers,
            @NotNull List<String> deadTrackers,
            boolean downloadVideoOnly,
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
     * @param maxPeerConnectionsPerTorrent ceiling on simultaneous peers for one torrent. A
     *     <em>connection</em> ceiling, which is not the same thing as a transfer ceiling — see
     *     {@code maxActivePeerConnectionsPerTorrent}, which is the one that governs speed.
     * @param maxActivePeerConnectionsPerTorrent how many of those connections may be assigned pieces
     *     at once. The library defaults this to 10 and it is the real throughput ceiling: raising
     *     {@code maxPeerConnectionsPerTorrent} alone buys established connections that sit idle. It
     *     costs no memory to raise, because the per-connection buffers are already allocated.
     * @param maxPeerConnections ceiling across every torrent at once. One runtime is shared by all
     *     downloads, so this is what two concurrent ingestions actually compete for; keep it above
     *     the per-torrent ceiling or the second ingestion starves.
     * @param maxPendingConnectionRequests how many connections may be in the process of being opened.
     *     Governs how fast the swarm is populated, not how large it gets.
     * @param peersPerTrackerRequest how many peers to ask each tracker for per announce.
     * @param maxIoQueueSize ceiling on blocks waiting to be written to disk. The library leaves this
     *     unbounded, which means a slow disk backs up in heap with nothing to stop it.
     */
    public record Network(
            @NotNull Encryption encryption,
            boolean disableLocalServiceDiscovery,
            boolean disablePeerExchange,
            String acceptorAddress,
            @Positive int acceptorPort,
            @Positive int maxPeerConnectionsPerTorrent,
            @Positive int maxActivePeerConnectionsPerTorrent,
            @Positive int maxPeerConnections,
            @Positive int maxPendingConnectionRequests,
            @Positive int peersPerTrackerRequest,
            @Positive int maxIoQueueSize,
            @NotNull Duration trackerTimeout) {}

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
    /**
     * Mirrors {@link com.azt.streaming.transcoding.domain.UndecodableAudioPolicy}.
     *
     * <p>Declared here rather than imported for the same reason {@link Encryption} is: nothing in
     * {@code shared.config} may depend on a slice, and {@code ArchitectureTest} enforces it. The
     * transcoding adapter maps this by name.
     */
    public enum UndecodableAudioPolicy {
        PASSTHROUGH,
        DROP,
        FAIL
    }

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
