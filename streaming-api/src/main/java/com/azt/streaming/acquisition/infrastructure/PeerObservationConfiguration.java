package com.azt.streaming.acquisition.infrastructure;

import com.azt.streaming.acquisition.domain.PeerObservationSink;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Where peer sightings go when the provider log is switched off.
 *
 * <p>Its own configuration class rather than a bean on one of the engines, because both engines
 * report peers and neither owns the question of what happens to them. It lived on
 * {@link BtRuntimeConfiguration} while that was the only engine there was, which meant choosing the
 * brokered one left the context with a downloader that reports sightings and nothing to report them
 * to.
 *
 * <p>Gated on the same flag as the log rather than on {@code @ConditionalOnMissingBean}, which was
 * the first attempt and does not work here: that condition is evaluated in the order configuration
 * classes happen to be processed, which is only defined for auto-configuration. Between two ordinary
 * {@code @Configuration} classes it raced, and lost — the context failed to start with two candidate
 * sinks. Two conditions on one property cannot both be true.
 */
@Configuration
public class PeerObservationConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "aztcast.streaming.providers",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true)
    public PeerObservationSink noOpPeerObservationSink() {
        return PeerObservationSink.NONE;
    }
}
