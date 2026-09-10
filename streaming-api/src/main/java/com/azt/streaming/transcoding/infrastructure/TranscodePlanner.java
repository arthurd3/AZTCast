package com.azt.streaming.transcoding.infrastructure;

import com.azt.streaming.shared.config.StreamingProperties;
import com.azt.streaming.transcoding.domain.AudioPlan;
import com.azt.streaming.transcoding.domain.AudioPlanner;
import com.azt.streaming.transcoding.domain.AudioPreferences;
import com.azt.streaming.transcoding.domain.EncoderChoice;
import com.azt.streaming.transcoding.domain.HlsRendition;
import com.azt.streaming.transcoding.domain.LadderPlanner;
import com.azt.streaming.transcoding.domain.PlannedRendition;
import com.azt.streaming.transcoding.domain.ProbedSource;
import com.azt.streaming.transcoding.domain.SubtitlePlan;
import com.azt.streaming.transcoding.domain.TranscodePlan;
import com.azt.streaming.transcoding.domain.TranscodingException;
import com.azt.streaming.transcoding.domain.UndecodableAudioPolicy;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Turns configuration plus a probed source plus the binary's own capabilities into one plan.
 *
 * <p>The join between the three used to happen nowhere. Configuration named an encoder and hoped;
 * the source was probed for one boolean; the binary was never asked anything at all. Everything that
 * could only be discovered by running ffmpeg was discovered by running ffmpeg on a real job, which
 * is why a missing E-AC-3 decoder cost a 756 MB download before it cost an error message.
 */
@Component
@Slf4j
public class TranscodePlanner {

    /**
     * The preset {@code auto} resolves to for encoders that have one.
     *
     * <p>{@code veryfast} rather than the {@code medium} an unset x264 defaults to. At the bitrates
     * this ladder targets the quality difference is a few percent and only at the bottom rungs,
     * while the encode time difference is a multiple — and this pipeline runs two concurrent
     * transcodes on a box that is also seeding a torrent.
     */
    private static final String DEFAULT_PRESET = "veryfast";

    /** Encoders whose {@code -preset} vocabulary is x264's. Anything else keeps whatever it is given. */
    private static final List<String> X264_FAMILY = List.of("libx264", "libx265");

    private final StreamingProperties.Ffmpeg config;
    private final FfmpegCapabilities capabilities;
    private final List<HlsRendition> ladder;
    private final AudioPreferences audioPreferences;
    private final Duration segmentDuration;

    private volatile EncoderChoice encoder;

    public TranscodePlanner(StreamingProperties properties, FfmpegCapabilities capabilities) {
        this.config = properties.ffmpeg();
        this.capabilities = capabilities;
        this.segmentDuration = config.segmentDuration();
        this.ladder = config.renditions().stream()
                .map(r -> new HlsRendition(r.name(), r.width(), r.height(), r.videoBitrateKbps()))
                .toList();
        StreamingProperties.Audio audio = config.audio();
        this.audioPreferences = new AudioPreferences(
                audio.encoderPreference(),
                audio.fixedChannels(),
                audio.fixedSampleRate(),
                audio.bitrateKbpsPerChannel(),
                audio.maxBitrateKbps(),
                // Mapped by name: shared.config declares its own copy of this enum because nothing
                // there may depend on a slice, and ArchitectureTest enforces it.
                UndecodableAudioPolicy.valueOf(audio.onUndecodable().name()));
    }

    /**
     * The video encoder this host will use, resolved once and remembered.
     *
     * @throws TranscodingException when the configuration names nothing this build carries — which
     *     is worth failing on, loudly and at the first job, rather than handing ffmpeg a codec name
     *     it will reject after a ladder has been planned around it
     */
    public EncoderChoice encoder() {
        EncoderChoice current = encoder;
        if (current == null) {
            synchronized (this) {
                current = encoder;
                if (current == null) {
                    current = resolveEncoder();
                    encoder = current;
                }
            }
        }
        return current;
    }

    /** The whole plan for one source. */
    public TranscodePlan plan(ProbedSource source) {
        List<PlannedRendition> renditions = LadderPlanner.plan(ladder, source);
        AudioPlan audio = AudioPlanner.plan(source, audioPreferences, capabilities.support());
        List<SubtitlePlan> subtitles = config.subtitles().enabled()
                ? source.textSubtitles().stream().map(SubtitlePlan::from).toList()
                : List.of();
        return new TranscodePlan(renditions, audio, subtitles, source.framesPerSegment(segmentDuration.toSeconds()));
    }

    private EncoderChoice resolveEncoder() {
        String name = config.autoVideoCodec()
                ? capabilities
                        .firstEncoder(config.videoCodecPreference())
                        .orElseThrow(() -> new TranscodingException(
                                ("None of the configured H.264 encoders %s is present in '%s'. Install a build that"
                                                + " carries one, or set aztcast.streaming.ffmpeg.video-codec to an"
                                                + " encoder this one has.")
                                        .formatted(config.videoCodecPreference(), config.binary())))
                : config.videoCodec();

        if (!config.autoVideoCodec() && !capabilities.support().canEncode(name)) {
            throw new TranscodingException(
                    "aztcast.streaming.ffmpeg.video-codec is pinned to '%s', which '%s' does not carry."
                            .formatted(name, config.binary()));
        }

        EncoderChoice choice = new EncoderChoice(name, presetFor(name));
        log.info(
                "Video encoder resolved to {}{}",
                choice.name(),
                choice.hasPreset() ? " (preset " + choice.preset() + ")" : "");
        reportHardware();
        return choice;
    }

    /**
     * The preset to hand this encoder, or null when it has none.
     *
     * <p>An explicitly configured preset is passed through even to an encoder whose vocabulary this
     * does not recognise, because that is the operator naming something for a build we cannot see.
     * {@code auto} only ever produces a value for encoders whose presets are x264's, since a preset
     * name is not portable — SVT-AV1's are integers.
     */
    private String presetFor(String name) {
        if (!capabilities.acceptsPreset(name)) {
            return null;
        }
        if (!config.autoPreset()) {
            return config.preset();
        }
        return X264_FAMILY.contains(name.toLowerCase(Locale.ROOT)) ? DEFAULT_PRESET : null;
    }

    /**
     * Logs what hardware encoding this host could offer, and uses none of it.
     *
     * <p>Detection now, encoding later. The distinction earned its own probe: on the machine this
     * was written on {@code h264_vaapi} is listed by {@code -encoders} and fails every attempt to
     * open with {@code Function not implemented}, because the installed Mesa driver exposes no H.264
     * profile at all. A listing says what was compiled in; only an encode says what works.
     */
    private void reportHardware() {
        if (!config.hardware().enabled()) {
            return;
        }
        Optional<String> working = Stream.of("h264_vaapi", "h264_qsv", "h264_nvenc", "h264_amf")
                .filter(name -> capabilities.hardwareEncoderWorks(name, config.hardware().device()))
                .findFirst();
        working.ifPresentOrElse(
                name -> log.info("Hardware H.264 encoding is available here via {} (not yet used)", name),
                () -> log.info("No working hardware H.264 encoder on this host; encoding in software"));
    }
}
