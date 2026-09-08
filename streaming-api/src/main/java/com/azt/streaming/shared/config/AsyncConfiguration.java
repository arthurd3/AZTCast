package com.azt.streaming.shared.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The executor that backs transcoding.
 *
 * <p>This is the only {@code @EnableAsync} in the application; it used to be declared here <em>and
 * </em> on the main class. Note that removing both would silently turn every {@code @Async} call
 * into a blocking one, so the ingestion request would hang for the whole torrent download.
 *
 * <p>The bean is deliberately <em>not</em> called {@code taskExecutor} any more. That is the name
 * {@code AsyncAnnotationBeanPostProcessor} resolves by default, so owning it meant this pool also
 * replaced Boot's auto-configured executor for every other async concern in the container. It is
 * named explicitly instead, and {@code @Async} call sites qualify it by name.
 */
@Configuration
@EnableAsync
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
