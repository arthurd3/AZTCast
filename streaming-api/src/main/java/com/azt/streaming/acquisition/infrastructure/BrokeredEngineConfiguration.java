package com.azt.streaming.acquisition.infrastructure;

import com.azt.streaming.acquisition.domain.PeerObservationSink;
import com.azt.streaming.acquisition.domain.SwarmSelfView;
import com.azt.streaming.acquisition.domain.TorrentDownloader;
import com.azt.streaming.shared.config.StreamingProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires acquisition to the out-of-process engine, and to nothing else.
 *
 * <p>The condition on this class and the matching one on {@link BtRuntimeConfiguration} are what
 * make the two engines exclusive, and that exclusivity is the point rather than tidiness. Left
 * unconditional, choosing the brokered engine would still have started a {@code BtRuntime} in this
 * JVM — which would open the peer port here, bootstrap DHT here, and leave the process doing exactly
 * the swarm-facing work the engine exists to move out. The sandbox would be real and would be
 * guarding an empty room.
 */
@Configuration
@ConditionalOnProperty(prefix = "aztcast.streaming.torrent", name = "engine", havingValue = "brokered")
@Slf4j
public class BrokeredEngineConfiguration {

    @Bean
    public BrokeredSwarmSelfView brokeredSwarmSelfView() {
        return new BrokeredSwarmSelfView();
    }

    /**
     * Published as the port, so the provenance page reads it without knowing which engine filled it.
     */
    @Bean
    public SwarmSelfView swarmSelfView(BrokeredSwarmSelfView brokered) {
        return brokered;
    }

    @Bean
    public TorrentDownloader torrentDownloader(
            PeerObservationSink peerObservations,
            BrokeredSwarmSelfView selfView,
            MagnetTrackerInjector trackerInjector,
            StreamingProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        log.info(
                "Acquisition is brokered: the swarm runs out of process, reached on {}",
                properties.torrent().engineSocket());
        return new BrokeredTorrentDownloader(
                peerObservations, selfView, trackerInjector, properties, objectMapper, clock);
    }
}
