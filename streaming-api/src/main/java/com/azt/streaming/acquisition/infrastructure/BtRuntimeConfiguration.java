package com.azt.streaming.acquisition.infrastructure;

import bt.dht.DHTConfig;
import bt.dht.DHTModule;
import bt.module.ProtocolModule;
import bt.module.ServiceModule;
import bt.protocol.crypto.EncryptionPolicy;
import bt.runtime.BtRuntime;
import bt.runtime.Config;
import com.azt.streaming.acquisition.domain.PeerObservationSink;
import com.azt.streaming.acquisition.domain.SwarmSelfView;
import com.azt.streaming.shared.config.StreamingProperties;
import com.google.inject.Module;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the {@code bt} BitTorrent runtime.
 *
 * <p>There used to be no runtime here at all. Every download called {@code Bt.client()}, which
 * builds a private {@code BtRuntime} and never hands it back — so each concurrent ingestion stood up
 * an entire independent BitTorrent stack: its own selector threads, its own DHT node, and its own
 * attempt to bind the same acceptor port, which only the first one won. It also put the library's
 * event bus and its extension switches permanently out of reach, because both live on the runtime
 * that builder hides.
 *
 * <p>One runtime, built here and shared, fixes all of that. Clients are cheap and still per-download
 * ({@code Bt.client(runtime)}); the swarm-facing machinery is built once.
 */
@Configuration
@Slf4j
public class BtRuntimeConfiguration {

    /**
     * The runtime every download attaches a client to.
     *
     * <p>Spring owns the shutdown. The library also registers its own JVM hook, which stays as a
     * backstop for an abnormal exit, but an orderly context close should stop the swarm before the
     * threads it is using go away.
     */
    @Bean(destroyMethod = "shutdown")
    public BtRuntime btRuntime(Config btConfig, List<Module> btModules, StreamingProperties properties) {
        StreamingProperties.Network network = properties.torrent().network();

        // Without this the runtime is not actually shared. BtClient.stop() calls
        // BtRuntime.detachClient, which tears down the *whole* runtime as soon as the last client
        // detaches — so the first download to finish shut down the DHT node, the peer registry and
        // the connection acceptor that every later download depends on. It is the source of the
        // RejectedExecutionException storm from bt.peer.peer-collector at the end of a download,
        // and of the second ingestion in a process being quietly degraded: DefaultClient sees a
        // stopped runtime and restarts it, which re-runs the startup hooks against Guice singletons
        // whose executors were already shutdownNow()n. Nothing throws. It simply does not work.
        //
        // Spring owns the shutdown, as the javadoc above says it should.
        var builder = BtRuntime.builder(btConfig).autoLoadModules().disableAutomaticShutdown();
        btModules.forEach(builder::module);

        // Both are on by default in the library and are only reachable from this builder — which is
        // the other half of why the runtime is built here rather than left to Bt.client().
        if (network.disableLocalServiceDiscovery()) {
            builder.disableLocalServiceDiscovery();
        }
        if (network.disablePeerExchange()) {
            builder.disablePeerExchange();
        }

        // Every number that governs throughput, on one line. Without it there is no way to tell a
        // tuned runtime from a defaulted one except by watching a download and guessing.
        log.info(
                "BitTorrent runtime: port={} encryption={} lsd={} pex={} bind={}"
                        + " peers={}/torrent ({} active, {} global) pending={} trackerBatch={} ioQueue={}"
                        + " trackerTimeout={}",
                btConfig.getAcceptorPort(),
                btConfig.getEncryptionPolicy(),
                network.disableLocalServiceDiscovery() ? "off" : "on",
                network.disablePeerExchange() ? "off" : "on",
                btConfig.getAcceptorAddress(),
                btConfig.getMaxPeerConnectionsPerTorrent(),
                btConfig.getMaxConcurrentlyActivePeerConnectionsPerTorrent(),
                btConfig.getMaxPeerConnections(),
                btConfig.getMaxPendingConnectionRequests(),
                btConfig.getNumberOfPeersToRequestFromTracker(),
                btConfig.getMaxIOQueueSize(),
                btConfig.getTrackerTimeout());

        return builder.build();
    }

    @Bean
    public Config btConfig(StreamingProperties properties) {
        StreamingProperties.Network network = properties.torrent().network();

        Config config =
                new Config() {
                    @Override
                    public int getNumOfHashingThreads() {
                        // One per core, not two. Hash verification is a burst at the end of a piece,
                        // not a continuous load, and this pool competes with ffmpeg for the same
                        // cores — oversubscribing it slows the encode without speeding the download.
                        return Runtime.getRuntime().availableProcessors();
                    }
                };

        // Named to match the library's enum, mapped here so shared.config carries no bt types.
        config.setEncryptionPolicy(EncryptionPolicy.valueOf(network.encryption().name()));
        config.setAcceptorPort(network.acceptorPort());
        config.setMaxPeerConnectionsPerTorrent(network.maxPeerConnectionsPerTorrent());

        // The throughput ceiling, and the reason a 200-peer setting used to behave like a 10-peer
        // one: the library assigns pieces to at most this many connections at a time and leaves the
        // rest established but idle. It defaults to 10.
        config.setMaxConcurrentlyActivePeerConnectionsPerTorrent(network.maxActivePeerConnectionsPerTorrent());
        config.setMaxPeerConnections(network.maxPeerConnections());
        config.setMaxPendingConnectionRequests(network.maxPendingConnectionRequests());
        config.setNumberOfPeersToRequestFromTracker(network.peersPerTrackerRequest());
        config.setMaxIOQueueSize(network.maxIoQueueSize());

        config.setTrackerTimeout(network.trackerTimeout());

        resolveBindAddress(network.acceptorAddress()).ifPresent(choice -> {
            config.setAcceptorAddress(choice.address());
            log.info(
                    "Binding BitTorrent to {} on {} — {}",
                    choice.address().getHostAddress(),
                    EgressInterface.interfaceNameOf(choice.address()),
                    choice.reason());
        });

        return config;
    }

    /**
     * The address to bind to: what was configured, else what this host actually goes out on.
     *
     * <p>Empty only when neither is available, which leaves the library's own first-interface scan
     * in place — the behaviour everything had before, rather than a startup failure.
     */
    private static Optional<EgressInterface.Choice> resolveBindAddress(String configured) {
        Optional<InetAddress> explicit = bindAddress(configured);
        if (explicit.isPresent()) {
            return explicit.map(address -> new EgressInterface.Choice(address, "configured explicitly"));
        }
        Optional<EgressInterface.Choice> detected = EgressInterface.detect();
        detected.ifPresent(choice -> {
            if (EgressInterface.looksVirtual(EgressInterface.interfaceNameOf(choice.address()))) {
                log.warn(
                        "The route out of this host runs over {}, which looks like a container bridge."
                                + " Inbound peer connections will not reach it. Set"
                                + " aztcast.streaming.torrent.network.acceptor-address if that is wrong.",
                        EgressInterface.interfaceNameOf(choice.address()));
            }
        });
        return detected;
    }

    /**
     * The local address to bind to, when one is configured.
     *
     * <p>This is the only setting here that changes which address peers see, because the library
     * binds outgoing connections to it as well as the listening socket. Pointing it at a tunnel is
     * what keeps torrent traffic off every other interface.
     *
     * <p>An unresolvable address is a warning rather than a startup failure, deliberately: the
     * interface may not exist yet on a host that brings its tunnel up alongside this process, and
     * refusing to start would turn a degraded configuration into an outage. It is logged loudly
     * because the fallback binds to whatever the library picks, which may not be the tunnel.
     */
    private static Optional<InetAddress> bindAddress(String configured) {
        if (configured == null || configured.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByName(configured.trim()));
        } catch (UnknownHostException e) {
            log.warn(
                    "Cannot resolve acceptor address '{}'; binding to a library-chosen interface instead."
                            + " Torrent traffic will not be confined to it.",
                    configured,
                    e);
            return Optional.empty();
        }
    }

    /**
     * Everything learned about a live connection, before anything writes it down.
     *
     * <p>Also published as {@link SwarmSelfView}, which is the half of it anything outside this
     * slice is allowed to see.
     */
    @Bean
    public PeerFactsRegistry peerFactsRegistry() {
        return new PeerFactsRegistry();
    }

    @Bean
    public SwarmSelfView swarmSelfView(PeerFactsRegistry peerFactsRegistry) {
        return peerFactsRegistry;
    }

    /**
     * Two hooks into the wire, because what we want arrives in two places the library keeps nothing
     * from: the peer_id on the base handshake, and the byte counters behind a message context.
     *
     * <p>They are registered through different extenders and there is no choice about it —
     * {@code ProtocolModule} owns handshake handlers, {@code ServiceModule} owns messaging agents,
     * and a handshake never reaches a messaging agent.
     */
    @Bean
    public Module btPeerInspectionModule(PeerFactsRegistry peerFactsRegistry) {
        return binder -> {
            ServiceModule.extend(binder).addMessagingAgent(new PeerWireAgent(peerFactsRegistry));
            ProtocolModule.extend(binder).addHandshakeHandler(new PeerHandshakeInspector(peerFactsRegistry));
        };
    }

    /**
     * Where peer sightings go when the provider log is switched off.
     *
     * <p>Gated on the same flag as the log rather than on {@code @ConditionalOnMissingBean}, which
     * was the first attempt and does not work here: that condition is evaluated in the order
     * configuration classes happen to be processed, which is only defined for auto-configuration.
     * Between two ordinary {@code @Configuration} classes it raced, and lost — the context failed to
     * start with two candidate sinks. Two conditions on one property cannot both be true.
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "aztcast.streaming.providers",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true)
    public PeerObservationSink noOpPeerObservationSink() {
        return PeerObservationSink.NONE;
    }

    @Bean
    public Module btDhtModule() {
        return new DHTModule(
                new DHTConfig() {
                    @Override
                    public boolean shouldUseRouterBootstrap() {
                        return true;
                    }
                });
    }
}
