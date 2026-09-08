package com.azt.streaming.transcoding.infrastructure;

import com.azt.streaming.shared.config.StreamingProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports whether the configured ffmpeg binary is actually present.
 *
 * <p>ffmpeg was an invisible runtime dependency: nothing declared it, nothing checked for it, and a
 * missing binary surfaced as an opaque failure after a torrent had already been downloaded. Now it
 * is a readiness signal — a container built from the wrong base image fails its probe instead of
 * accepting work it cannot do.
 */
@Component("ffmpeg")
public class FfmpegHealthIndicator implements HealthIndicator {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    private final String binary;
    private final String videoCodec;

    public FfmpegHealthIndicator(StreamingProperties properties) {
        this.binary = properties.ffmpeg().binary();
        this.videoCodec = properties.ffmpeg().videoCodec();
    }

    @Override
    public Health health() {
        // Checking that the binary runs is not enough: a patent-free ffmpeg build starts happily
        // and then fails every encode with "Error selecting an encoder". Probe the codec itself.
        return probe(binary, "-hide_banner", "-h", "encoder=" + videoCodec)
                .map(output -> output.contains("Encoder " + videoCodec)
                        ? Health.up().withDetail("binary", binary).withDetail("videoCodec", videoCodec).build()
                        : Health.down()
                                .withDetail("binary", binary)
                                .withDetail("videoCodec", videoCodec)
                                .withDetail("reason", "encoder not available in this ffmpeg build")
                                .build())
                .orElseGet(() -> Health.down()
                        .withDetail("binary", binary)
                        .withDetail("reason", "could not run the ffmpeg binary")
                        .build());
    }

    private Optional<String> probe(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            return process.exitValue() == 0 ? Optional.of(output) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return Optional.empty();
        }
    }
}
