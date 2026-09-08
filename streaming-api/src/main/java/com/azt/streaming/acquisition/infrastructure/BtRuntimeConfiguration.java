package com.azt.streaming.acquisition.infrastructure;

import bt.dht.DHTConfig;
import bt.dht.DHTModule;
import bt.runtime.Config;
import com.google.inject.Module;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the {@code bt} BitTorrent runtime.
 *
 * <p>These were anonymous classes assigned to non-final instance fields on the downloader, which
 * meant the library's configuration was buried inside the class that used it and could not be
 * overridden or inspected. They are ordinary beans now.
 */
@Configuration
public class BtRuntimeConfiguration {

    @Bean
    public Config btConfig() {
        return new Config() {
            @Override
            public int getNumOfHashingThreads() {
                return Runtime.getRuntime().availableProcessors() * 2;
            }
        };
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
