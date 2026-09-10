package com.azt.streaming.transcoding.domain;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What the ffmpeg binary on this host can actually do, as opposed to what the configuration hopes.
 *
 * <p>The pipeline used to assume its build. It named {@code libx264} in {@code application.yml} and
 * left a hand-edited Spring profile to say "actually, libopenh264 here" — which only covers the one
 * distribution someone remembered, and only for the encoder. Nothing at all covered <em>decoders</em>,
 * and that is the half that failed: Fedora's patent-free build carries no {@code eac3} decoder, so a
 * 756 MB download of a perfectly ordinary AMZN WEB-DL ended in {@code exited with code 234} after the
 * bandwidth had already been spent.
 *
 * <p>So the build is asked, once, and every decision downstream reads the answer. A rung is planned
 * against the encoders that exist; an audio track is planned against the decoders that exist.
 *
 * <h2>Unknown is not "no"</h2>
 *
 * <p>{@code known} is false when the listing could not be read — a stub binary in a test, a build
 * whose output this parser does not recognise, a binary that hangs. In that state every query answers
 * <em>yes</em>, which puts the pipeline back to exactly the behaviour it had before any of this
 * existed: trust the configuration and let ffmpeg complain. Guessing "no" from a failed probe would
 * turn one unreadable listing into a service that refuses every file it is given.
 *
 * @param encoders encoder names as {@code ffmpeg -encoders} lists them, lowercased
 * @param decoders codec names this build can decode, lowercased — the codec, not the decoder: a
 *     build carrying only {@code libopenh264} can still decode {@code h264}, and asking about the
 *     decoder's name would miss that
 * @param hwaccels methods from {@code ffmpeg -hwaccels}, lowercased. Presence here means the build
 *     was compiled with it, which is emphatically not the same as it working — see
 *     {@code FfmpegCapabilities} for why that distinction earned its own probe.
 * @param known whether the listings were read at all
 */
public record FfmpegSupport(Set<String> encoders, Set<String> decoders, Set<String> hwaccels, boolean known) {

    public FfmpegSupport {
        encoders = Set.copyOf(encoders);
        decoders = Set.copyOf(decoders);
        hwaccels = Set.copyOf(hwaccels);
    }

    /** The permissive fallback: nothing was read, so nothing is refused. */
    public static FfmpegSupport unknown() {
        return new FfmpegSupport(Set.of(), Set.of(), Set.of(), false);
    }

    /** Whether a stream in {@code codecName} can be decoded — i.e. re-encoded, filtered or scaled. */
    public boolean canDecode(String codecName) {
        return !known || contains(decoders, codecName);
    }

    /** Whether {@code encoderName} can be handed to {@code -c:v} or {@code -c:a}. */
    public boolean canEncode(String encoderName) {
        return !known || contains(encoders, encoderName);
    }

    public boolean hasHwaccel(String method) {
        return contains(hwaccels, method);
    }

    /**
     * The first encoder in {@code preference} this build carries.
     *
     * <p>Empty only when the listing was read and none of them are present — which is a real
     * outcome worth failing on, because the alternative is handing ffmpeg a codec name it will
     * reject after the ladder has already been planned. When nothing is known, the first preference
     * is returned on the same optimistic principle as {@link #canEncode}.
     */
    public Optional<String> firstEncoder(List<String> preference) {
        if (preference.isEmpty()) {
            return Optional.empty();
        }
        if (!known) {
            return Optional.of(preference.getFirst());
        }
        return preference.stream().filter(this::canEncode).findFirst();
    }

    private static boolean contains(Set<String> names, String candidate) {
        return candidate != null && names.contains(candidate.toLowerCase(Locale.ROOT));
    }
}
