package com.azt.streaming.transcoding.infrastructure;

import com.azt.streaming.shared.config.StreamingProperties;
import com.azt.streaming.transcoding.domain.EncoderChoice;
import com.azt.streaming.transcoding.domain.FfmpegSupport;
import java.util.List;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports what the configured ffmpeg binary can actually do.
 *
 * <p>ffmpeg was an invisible runtime dependency: nothing declared it, nothing checked for it, and a
 * missing binary surfaced as an opaque failure after a torrent had already been downloaded. Then it
 * checked one encoder. Now it reports the whole picture, because the encoder was never the half that
 * failed — a build with a perfectly good H.264 encoder and no E-AC-3 decoder is exactly what shipped
 * the defect this was all written for, and nothing on this endpoint would have said so.
 *
 * <p>A missing decoder is a detail, not a DOWN. The pipeline adapts to it now — it copies the track
 * through, or drops it, per configuration — so a host without one is degraded rather than broken,
 * and taking the service out of the load balancer over it would be the wrong call. No usable H.264
 * encoder at all is a genuine DOWN: there is no ladder to build without one.
 */
@Component("ffmpeg")
public class FfmpegHealthIndicator implements HealthIndicator {

    /**
     * Decoders worth naming in a health report.
     *
     * <p>Not an exhaustive list — the point is to answer "why did that file come out silent" at a
     * glance, and these are the codecs the sources this service is pointed at actually carry.
     */
    private static final List<String> NOTABLE_DECODERS =
            List.of("h264", "hevc", "av1", "vp9", "aac", "ac3", "eac3", "dts", "truehd", "opus", "flac", "mp3");

    private final String binary;
    private final FfmpegCapabilities capabilities;
    private final TranscodePlanner planner;
    private final StreamingProperties.Ffmpeg config;

    public FfmpegHealthIndicator(
            StreamingProperties properties, FfmpegCapabilities capabilities, TranscodePlanner planner) {
        this.binary = properties.ffmpeg().binary();
        this.config = properties.ffmpeg();
        this.capabilities = capabilities;
        this.planner = planner;
    }

    @Override
    public Health health() {
        FfmpegSupport support = capabilities.support();
        if (!support.known()) {
            return Health.down()
                    .withDetail("binary", binary)
                    .withDetail("reason", "could not read the codec listings from the ffmpeg binary")
                    .build();
        }

        EncoderChoice encoder;
        try {
            encoder = planner.encoder();
        } catch (RuntimeException e) {
            return Health.down()
                    .withDetail("binary", binary)
                    .withDetail("videoCodecPreference", config.videoCodecPreference())
                    .withDetail("reason", e.getMessage())
                    .build();
        }

        Health.Builder health = Health.up()
                .withDetail("binary", binary)
                .withDetail("videoEncoder", encoder.name())
                .withDetail("preset", encoder.hasPreset() ? encoder.preset() : "none")
                .withDetail(
                        "audioEncoder",
                        support.firstEncoder(config.audio().encoderPreference()).orElse("none"))
                .withDetail("hwaccels", support.hwaccels());

        List<String> missing = NOTABLE_DECODERS.stream()
                .filter(codec -> !support.canDecode(codec))
                .toList();
        if (!missing.isEmpty()) {
            // Named rather than counted, because the whole value of this line is that someone
            // reading it recognises "eac3" as the reason their WEB-DL has no sound.
            health.withDetail("missingDecoders", missing)
                    .withDetail(
                            "onUndecodableAudio",
                            config.audio().onUndecodable().name().toLowerCase(java.util.Locale.ROOT));
        }
        return health.build();
    }
}
