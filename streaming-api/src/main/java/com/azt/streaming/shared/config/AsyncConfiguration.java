package com.azt.streaming.shared.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The executor that backs transcoding.
 *
 * <p>This is the only {@code @EnableAsync} in the application; it used to be declared here <em>and
 * </em> on the main class. Note that removing both would silently turn every {@code @Async} call
 * into a blocking one, so the ingestion request would hang for the whole torrent download.
 *
 * <p>The bean is deliberately <em>not</em> called {@code taskExecutor} any more, and call sites
 * qualify it by name. To be precise about what that does and does not buy: Boot's
 * {@code applicationTaskExecutor} is suppressed either way, because
 * {@code TaskExecutionAutoConfiguration} backs off on {@code @ConditionalOnMissingBean(Executor
 * .class)} — a condition on type, not on name (verified: this is the only Executor in the context).
 * What the explicit name buys is that this pool stops being the silent default for every future
 * {@code @Async} or {@code @Scheduled} method: anything that wants these threads has to say so, and
 * a second executor can be introduced later without quietly re-pointing existing call sites.
 */
@Configuration
@EnableAsync
// Scheduling runs on Boot's own single-thread scheduler, NOT the transcoding pool below.
// Sharing them would let a reaper run block an encode, or an encode starve the reaper.
@EnableScheduling
public class AsyncConfiguration {

    /** Bean name that {@code @Async} call sites must qualify against. */
    public static final String TRANSCODING_EXECUTOR = "transcodingExecutor";

    @Bean(TRANSCODING_EXECUTOR)
    public Executor transcodingExecutor(StreamingProperties properties) {
        StreamingProperties.Transcoding.Pool pool = properties.transcoding().pool();

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(pool.coreSize());
        executor.setMaxPoolSize(pool.maxSize());
        executor.setQueueCapacity(pool.queueCapacity());
        executor.setThreadNamePrefix(pool.threadNamePrefix());
        executor.initialize();
        return executor;
    }
}
