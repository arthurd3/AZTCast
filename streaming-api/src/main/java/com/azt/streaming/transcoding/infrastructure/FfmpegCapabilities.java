package com.azt.streaming.transcoding.infrastructure;

import com.azt.streaming.shared.config.StreamingProperties;
import com.azt.streaming.transcoding.domain.FfmpegSupport;
import com.azt.streaming.transcoding.domain.TranscodingException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Asks the ffmpeg binary what it can do, once, and remembers the answer.
 *
 * <p>Everything downstream used to assume. {@code application.yml} named {@code libx264}, a
 * hand-edited {@code local} profile said "actually libopenh264 here", and nothing anywhere asked
 * about decoders — which is the half that failed in production, on the most ordinary file
 * imaginable. Adapting to the host rather than being configured for it is the difference between a
 * service that runs on one developer's Fedora and one that runs on whatever Linux it is put on.
 *
 * <h2>Reading the listings</h2>
 *
 * <p>{@code ffmpeg -encoders} and {@code -decoders} print a legend, a {@code ------} rule, and then
 * one line per entry: six flag characters, a name, and a description. The name is the
 * <em>implementation</em>, not the codec — {@code libfdk_aac} decodes {@code aac}, {@code libopenh264}
 * decodes {@code h264} — and the description says which codec that is, in a trailing
 * {@code (codec x)}. Indexing by implementation alone is how you conclude a build cannot decode
 * H.264 while it is decoding H.264, so both are indexed.
 *
 * <h2>Why an empty listing is not an empty build</h2>
 *
 * <p>Parsing nothing means the probe failed, not that the binary supports nothing: the test suite
 * points {@code binary} at {@code /bin/true}, and a future ffmpeg could reformat the table. Zero
 * parsed entries produces {@link FfmpegSupport#unknown()}, which answers yes to everything and puts
 * the pipeline back to its previous behaviour rather than making it refuse every file.
 */
@Component
@Slf4j
public class FfmpegCapabilities {

    /** Listing the codecs is a table lookup in a loaded binary; anything slower than this is broken. */
    private static final Duration LISTING_TIMEOUT = Duration.ofSeconds(15);

    /** A one-frame encode against real hardware. Slower than a listing, still not slow. */
    private static final Duration HARDWARE_PROBE_TIMEOUT = Duration.ofSeconds(20);

    /** The line that ends the legend and begins the entries. */
    private static final String LISTING_RULE = "------";

    /** {@code libfdk_aac  Fraunhofer FDK AAC (codec aac)} — the codec an implementation serves. */
    private static final Pattern CODEC_ALIAS = Pattern.compile("\\(codec ([^)\\s]+)\\)");

    /** {@code  -preset  <int>  E..V.......  Encoding preset} in an encoder's own option list. */
    private static final Pattern PRESET_OPTION = Pattern.compile("(?m)^\\s+-preset\\s");

    /** Six flag characters, a space, the name, then the description. */
    private static final Pattern LISTING_ENTRY = Pattern.compile("^\\s*[A-Z.]{6}\\s+(\\S+)\\s*(.*)$");

    private final String binary;
    private final ProcessRunner processRunner;
    private final Map<String, Boolean> hardwareProbes = new ConcurrentHashMap<>();
    private final Map<String, Boolean> presetProbes = new ConcurrentHashMap<>();

    private volatile FfmpegSupport support;

    public FfmpegCapabilities(StreamingProperties properties, ProcessRunner processRunner) {
        this.binary = properties.ffmpeg().binary();
        this.processRunner = processRunner;
    }

    /**
     * What this build supports.
     *
     * <p>Resolved on first use rather than at startup: the health indicator, the command builder and
     * the planners all want it, none of them wants the application context to fail because ffmpeg
     * was slow to answer, and a context that boots without it is still a context that can report the
     * problem on {@code /actuator/health}.
     */
    public FfmpegSupport support() {
        FfmpegSupport current = support;
        if (current == null) {
            synchronized (this) {
                current = support;
                if (current == null) {
                    current = probe();
                    support = current;
                }
            }
        }
        return current;
    }

    /**
     * Whether {@code encoder} not only exists but actually opens.
     *
     * <p>Both halves are needed and only for hardware. On the machine this was written on,
     * {@code h264_vaapi} appears in {@code -encoders} — the build was compiled with VAAPI — and
     * every attempt to use it fails with {@code Function not implemented}, because the installed
     * Mesa driver exposes no H.264 profile. A listing answers "was this compiled in"; only an encode
     * answers "will this work", and choosing a hardware encoder on the strength of the first is how
     * a pipeline ends up failing every job on a host that has a perfectly good software encoder.
     *
     * @param device the DRM render node to initialise, or null for encoders that need no device
     */
    public boolean hardwareEncoderWorks(String encoder, String device) {
        return hardwareProbes.computeIfAbsent(encoder, name -> {
            if (!support().canEncode(name)) {
                return false;
            }
            try {
                processRunner.run(oneFrameEncode(name, device), HARDWARE_PROBE_TIMEOUT);
                log.info("Hardware encoder {} opened successfully", name);
                return true;
            } catch (RuntimeException e) {
                log.info("Hardware encoder {} is listed but does not open on this host: {}", name, e.getMessage());
                return false;
            }
        });
    }

    /** A flat view for {@code /actuator/health}, so an operator can see what the host actually offers. */
    public Map<String, String> describe() {
        FfmpegSupport current = support();
        if (!current.known()) {
            return Map.of("capabilities", "unknown — the encoder and decoder listings could not be read");
        }
        return Map.of(
                "encoders", String.valueOf(current.encoders().size()),
                "decoders", String.valueOf(current.decoders().size()),
                "hwaccels", current.hwaccels().isEmpty() ? "none" : String.join(",", current.hwaccels()));
    }

    private FfmpegSupport probe() {
        Set<String> encoders = readListing("-encoders");
        Set<String> decoders = readListing("-decoders");
        Set<String> hwaccels = readHwaccels();

        if (encoders.isEmpty() && decoders.isEmpty()) {
            log.warn(
                    "Could not read the codec listings from '{}'. Codec availability will not be checked,"
                            + " and an unsupported source will fail inside ffmpeg as it did before.",
                    binary);
            return FfmpegSupport.unknown();
        }
        log.info(
                "ffmpeg capabilities: {} encoders, {} decoders, hwaccels=[{}]",
                encoders.size(),
                decoders.size(),
                String.join(",", hwaccels));
        return new FfmpegSupport(encoders, decoders, hwaccels, true);
    }

    private Set<String> readListing(String flag) {
        String output;
        try {
            output = processRunner.runCapturing(List.of(binary, "-hide_banner", flag), LISTING_TIMEOUT);
        } catch (TranscodingException e) {
            log.debug("Could not run '{} {}'", binary, flag, e);
            return Set.of();
        }
        if (output == null || output.isBlank()) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        boolean pastRule = false;
        for (String line : output.split("\n")) {
            if (!pastRule) {
                pastRule = line.strip().startsWith(LISTING_RULE);
                continue;
            }
            Matcher entry = LISTING_ENTRY.matcher(line);
            if (!entry.matches()) {
                continue;
            }
            // Both the implementation and the codec it serves, so `canEncode("libx264")` and
            // `canDecode("h264")` are each answerable from the same table.
            names.add(entry.group(1).toLowerCase(Locale.ROOT));
            Matcher alias = CODEC_ALIAS.matcher(entry.group(2));
            if (alias.find()) {
                names.add(alias.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private Set<String> readHwaccels() {
        try {
            String output = processRunner.runCapturing(List.of(binary, "-hide_banner", "-hwaccels"), LISTING_TIMEOUT);
            if (output == null) {
                return Set.of();
            }
            return output.lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    // The first line is the "Hardware acceleration methods:" header, and it is the
                    // only line carrying a colon.
                    .filter(line -> !line.endsWith(":"))
                    .map(line -> line.toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        } catch (TranscodingException e) {
            log.debug("Could not run '{} -hwaccels'", binary, e);
            return Set.of();
        }
    }

    /**
     * The smallest encode that still proves the encoder opens: two synthetic frames, uploaded to the
     * device if there is one, written nowhere.
     */
    private List<String> oneFrameEncode(String encoder, String device) {
        List<String> command = new java.util.ArrayList<>(List.of(binary, "-hide_banner", "-v", "error"));
        if (device != null && !device.isBlank()) {
            command.addAll(List.of("-init_hw_device", "vaapi=probe:" + device, "-filter_hw_device", "probe"));
        }
        command.addAll(List.of("-f", "lavfi", "-i", "testsrc2=s=320x240:r=25:d=0.1"));
        if (device != null && !device.isBlank()) {
            command.addAll(List.of("-vf", "format=nv12,hwupload"));
        }
        command.addAll(List.of("-c:v", encoder, "-frames:v", "1", "-f", "null", "-"));
        return List.copyOf(command);
    }

    /** Resolves the first usable encoder from {@code preference}, for callers that need a name. */
    public Optional<String> firstEncoder(List<String> preference) {
        return support().firstEncoder(preference);
    }

    /**
     * Whether {@code encoder} defines a {@code -preset} option.
     *
     * <p>Asked rather than assumed from the name. Handing a preset to an encoder that has none is
     * not fatal — ffmpeg prints "Codec AVOption preset ... has not been used for any stream" and
     * carries on — but that line appears on every encode and reads exactly like a defect, and the
     * whole point of this class is that the pipeline stops guessing what its binary supports.
     */
    public boolean acceptsPreset(String encoder) {
        return presetProbes.computeIfAbsent(encoder, name -> {
            try {
                String help = processRunner.runCapturing(
                        List.of(binary, "-hide_banner", "-h", "encoder=" + name), LISTING_TIMEOUT);
                return help != null && PRESET_OPTION.matcher(help).find();
            } catch (RuntimeException e) {
                log.debug("Could not read the option list for encoder {}", name, e);
                return false;
            }
        });
    }
}
