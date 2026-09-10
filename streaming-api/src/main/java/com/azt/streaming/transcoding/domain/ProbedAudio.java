package com.azt.streaming.transcoding.domain;

import java.util.Locale;
import java.util.Set;

/**
 * One audio track as ffprobe describes it.
 *
 * <p>{@code channels} is here because it is the field whose absence caused a silent defect: the
 * command builder hardcoded {@code -ac 2}, which is right for the 5.1 track this is usually pointed
 * at and wrong for a stereo one, where it asks a resampler to convert two channels into two.
 *
 * @param index the ordinal among audio streams, i.e. the {@code N} in {@code -map a:N} — not the
 *     stream index within the container, which counts video and subtitles too
 * @param codec {@code codec_name}, e.g. {@code eac3}
 * @param profile {@code profile}, e.g. {@code LC}; null when ffprobe reports none
 * @param channels channel count, 0 when unreported
 * @param sampleRate samples per second, 0 when unreported
 * @param language container language tag, e.g. {@code eng}; null when untagged
 * @param title container title tag, e.g. {@code Commentary}; null when untagged
 * @param isDefault whether the container marks this as the default track
 */
public record ProbedAudio(
        int index,
        String codec,
        String profile,
        int channels,
        int sampleRate,
        String language,
        String title,
        boolean isDefault) {

    /**
     * Codecs an fMP4 segment may legally carry, so a track in one of these can be remuxed into HLS
     * without ever being decoded.
     *
     * <p>This is the escape hatch for a build with no decoder for the track it was handed. Copying
     * is not as good as transcoding — a browser that cannot play E-AC-3 gets silence — but it is
     * enormously better than failing the ingestion, and the CODECS attribute tells the player which
     * it is getting before it commits to a rendition.
     */
    private static final Set<String> FMP4_AUDIO = Set.of("aac", "ac3", "eac3", "alac", "flac", "opus", "mp3");

    /** Whether this track can go into an HLS segment untouched. */
    public boolean isPackageable() {
        return codec != null && FMP4_AUDIO.contains(codec.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether copying it would also leave the CODECS attribute honest.
     *
     * <p>Strictly AAC-LC, and an unreported profile counts as no: {@link ProbedVideo#AAC_LC} is a
     * constant in the string this service writes, so copying an HE-AAC track would have the playlist
     * claim {@code mp4a.40.2} for something that is not.
     */
    public boolean isPlainAac() {
        return "aac".equalsIgnoreCase(codec) && "LC".equalsIgnoreCase(profile);
    }

    /**
     * The RFC 6381 identifier for this track as it would be copied.
     *
     * <p>Unlike video, these carry no profile or level suffix — the four-character sample entry name
     * is the whole identifier, which is why this can be answered without measuring the output.
     */
    public String codecsWhenCopied() {
        return switch (codec == null ? "" : codec.toLowerCase(Locale.ROOT)) {
            case "ac3" -> "ac-3";
            case "eac3" -> "ec-3";
            case "opus" -> "opus";
            case "flac" -> "fLaC";
            case "alac" -> "alac";
            case "mp3" -> "mp4a.40.34";
            default -> ProbedVideo.AAC_LC;
        };
    }
}
