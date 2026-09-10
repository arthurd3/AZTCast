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

    /**
     * x264's own factor, applied to the share of the machine a rung actually gets.
     *
     * <p>The insight is that x264's heuristic is not wrong — its input is. Left to itself it sizes
     * every encoder at {@code 1.5 x cores} against the whole machine, four times over, each instance
     * knowing nothing about the other three.
     */
    private static final double THREADS_PER_SHARE = 1.5;

    /**
     * Below two, x264 loses more to serialisation than it saves: one thread per rung measured 29%
     * slower than two on a four-core share.
     */
    private static final int MIN_THREADS_PER_RUNG = 2;

    /**
     * x264's own ceiling. Past it a frame-threaded encoder buys nothing but latency and buffers.
     */
    private static final int MAX_THREADS_PER_RUNG = 16;

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
        return new TranscodePlan(
                renditions,
                audio,
                subtitles,
                source.framesPerSegment(segmentDuration.toSeconds()),
                threadsPerRung(renditions));
    }

    /**
     * How many threads each encoded rung gets, sized to the machine this is running on.
     *
     * <p>Nothing set this before, so every libx264 instance sized its own pool at
     * {@code min(1.5 x cores, 16)} — independently, knowing nothing about the three or four other
     * instances in the same process. On a workstation that is harmless: the box has cores to spare
     * and the scheduler copes. On anything smaller it is the dominant cost. A four-rung ladder in a
     * two-core container runs twelve encoder threads on two cores and spends more time switching
     * between them than encoding, which measured <b>40.7s against 25.4s</b> for the same ladder with
     * one thread per rung.
     *
     * <p>The shape that fits every host measured is x264's own factor applied to the share of the
     * machine a rung actually gets, rather than to the whole machine four times over. Measured
     * against the default, same source, same rungs, alternating runs:
     *
     * <pre>
     *   cores   default   sized     gain
     *      2     39.0s     22.0s    1.77x
     *      4     20.3s     10.8s    1.88x
     *     32      4.3s      4.3s      —
     * </pre>
     *
     * <p>The workstation case is deliberately a draw rather than a win. There was no throughput
     * being lost there — the measurement that prompted this looked for one and did not find it, even
     * with two videos encoding at once — so the point of this is that the same code is no longer
     * badly wrong on a laptop.
     *
     * <p>The quota'd container is the worst case rather than merely the smallest, and the reason is
     * a mismatch: {@code availableProcessors} reads the cgroup quota, while x264 sizes itself from
     * {@code sched_getaffinity}, which {@code --cpus} does not touch. So a two-core container was
     * running a ladder's worth of threads sized for the host underneath it and then being throttled
     * onto two cores of runtime.
     *
     * <p>Zero means "let ffmpeg decide", which is what an operator gets by pinning
     * {@code encoder-threads} to 0 and what every host got before this existed.
     */
    private int threadsPerRung(List<PlannedRendition> renditions) {
        if (!config.autoEncoderThreads()) {
            return config.encoderThreadsOrZero();
        }
        long encoded = renditions.stream().filter(rung -> !rung.copyVideo()).count();
        if (encoded == 0) {
            return 0;
        }
        // Divided by the rungs of this ladder and deliberately not also by the concurrency ceiling.
        // Dividing by both was measured and rejected: it starves the common case, where one video is
        // encoding alone, to protect a case that turns out not to need protecting — two ladders at
        // once on a large host measured 9.6s uncapped against 9.8s capped, so the oversubscription
        // this was guarding against costs nothing there. Splitting the budget for it cost 13%.
        //
        // availableProcessors respects a container's CPU quota; x264, reading sched_getaffinity,
        // does not -- which is why a quota'd container is the worst case and not merely a small one.
        double share = Runtime.getRuntime().availableProcessors() / (double) encoded;
        return Math.clamp(
                (int) Math.round(THREADS_PER_SHARE * share), MIN_THREADS_PER_RUNG, MAX_THREADS_PER_RUNG);
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
