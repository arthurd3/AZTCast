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
        @NestedConfigurationProperty @Valid @NotNull Web web) {

    /**
     * Where media lives on disk. The two directories used to sit under unrelated top-level paths
     * ({@code ./download-torrents/} and {@code ./downloads/hls}); they are siblings now so a single
     * ignore rule, a single Docker volume and a single {@code du} target cover both.
     */
    public record Storage(@NotNull Path downloadsDir, @NotNull Path hlsDir) {}

    /** The external ffmpeg process and the HLS ladder it produces. */
    public record Ffmpeg(
            @NotBlank String binary,
            @NotBlank String probeBinary,
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
            @NotNull Duration progressLogInterval) {}

    /** Executor backing {@code @Async} transcoding work. */
    public record Transcoding(@NestedConfigurationProperty @Valid @NotNull Pool pool) {

        public record Pool(
                @Positive int coreSize,
                @Positive int maxSize,
                @Positive int queueCapacity,
                @NotBlank String threadNamePrefix) {}
    }

    /**
     * CORS origins. Empty is the correct production value: nginx reverse-proxies {@code /api} onto
     * the same origin that serves the player, so no cross-origin request is ever made.
     */
    public record Web(@NotNull List<String> allowedOrigins) {}
}
