package com.azt.streaming.transcoding.domain;

import java.util.Locale;

/**
 * What ffprobe actually found in a media file, as opposed to what was asked for.
 *
 * <p>The distinction is the point. The encoder is *asked* for Main@3.1, but what it produces depends
 * on the build and on the content: {@code libopenh264} silently falls back to Constrained Baseline
 * when no profile is requested, and every encoder derives the level from the resolution and bitrate
 * it ended up with, so a 240p rung comes out at level 2.1 no matter what the command said.
 *
 * <p>It is read in two places for two different reasons. Measuring an encoded rung needs only the
 * profile and level, because all that is being built is a CODECS string. Probing a <em>source</em>
 * needs the codec names and the dimensions too, because those decide whether the stream can be
 * copied into HLS untouched and how tall a ladder is worth building over it.
 *
 * @param hasVideo whether a video stream is present at all
 * @param hasAudio whether an audio stream is present — a torrent may legitimately carry neither
 * @param videoProfile H.264 profile as ffprobe names it, e.g. {@code Main}
 * @param videoLevel H.264 {@code level_idc}, e.g. {@code 31} for level 3.1
 * @param videoCodec {@code codec_name}, e.g. {@code h264} or {@code hevc}
 * @param audioCodec {@code codec_name} of the first audio stream, e.g. {@code aac}
 * @param audioProfile audio profile as ffprobe names it, e.g. {@code LC} or {@code HE-AAC}
 * @param width source width in pixels, 0 when not measured
 * @param height source height in pixels, 0 when not measured
 * @param bitRateKbps container bitrate, 0 when the file does not declare one
 */
public record ProbedVideo(
        boolean hasVideo,
        boolean hasAudio,
        String videoProfile,
        int videoLevel,
        String videoCodec,
        String audioCodec,
        String audioProfile,
        int width,
        int height,
        int bitRateKbps) {

    /**
     * A rung this service produced, measured for its CODECS attribute.
     *
     * <p>Dimensions and bitrate are left at zero rather than filled in, because on this path they
     * are already known — they came from the ladder that asked for them — and reading them back
     * would invite treating an output as if it were a source.
     */
    public static ProbedVideo measured(boolean hasAudio, String videoProfile, int videoLevel) {
        return new ProbedVideo(true, hasAudio, videoProfile, videoLevel, "h264", hasAudio ? "aac" : null, "LC", 0, 0, 0);
    }

    /**
     * Whether the video stream can be put into an HLS segment without re-encoding.
     *
     * <p>H.264 only. HLS carries HEVC and AV1 too, but the master playlist this service writes, the
     * CODECS strings it derives and the players it is tested against are all built around AVC, and
     * copying an HEVC stream into the top rung would produce a ladder whose best rung many browsers
     * silently refuse.
     */
    public boolean videoIsCopyable() {
        return hasVideo && "h264".equalsIgnoreCase(videoCodec) && width > 0 && height > 0;
    }

    /**
     * Whether the audio stream can be copied alongside it.
     *
     * <p>Strictly AAC-LC, and an unreported profile counts as no. {@link #AAC_LC} is a constant in
     * the CODECS string, so copying an HE-AAC track would have the playlist claim {@code mp4a.40.2}
     * for something that is not — the same class of lie about a stream that {@code codecs()} exists
     * to avoid. Re-encoding an unusual audio track costs almost nothing next to the video.
     */
    public boolean audioIsCopyable() {
        return hasAudio && "aac".equalsIgnoreCase(audioCodec) && "LC".equalsIgnoreCase(audioProfile);
    }

    /**
     * AAC-LC. Constant because every rung is encoded with {@code -c:a aac}, which is AAC-LC; if the
     * audio encoder ever becomes configurable this has to be measured like the video half.
     */
    public static final String AAC_LC = "mp4a.40.2";

    /** What the master playlist advertised for every rung before any of this was measured. */
    public static final String FALLBACK_CODECS = "avc1.4d001f," + AAC_LC;

    /**
     * The RFC 6381 codec string for this stream: {@code avc1.PPCCLL}, where PP is the profile_idc,
     * CC the constraint flags and LL the level_idc, each two hex digits.
     *
     * <p>This is what a player reads to decide whether it can play a rung before fetching a byte of
     * it. Getting it wrong in either direction is a real failure: overstate the level and a device
     * rejects a rendition it could have played; understate it and the device accepts one it cannot.
     */
    public String codecs() {
        String video = "avc1.%s%02x".formatted(profileAndConstraints(), videoLevel);
        // Advertising an audio codec on a rung that has no audio track is the same class of lie as
        // advertising the wrong level: the player provisions a decoder for something never sent.
        return hasAudio ? video + "," + AAC_LC : video;
    }

    private String profileAndConstraints() {
        return switch (videoProfile == null ? "" : videoProfile.toLowerCase(Locale.ROOT)) {
            // constraint_set1 is what makes Baseline "Constrained", and it is carried in the string.
            case "constrained baseline" -> "42e0";
            case "baseline" -> "4200";
            case "main" -> "4d00";
            case "high" -> "6400";
            case "high 10" -> "6e00";
            default -> "4d00";
        };
    }
}
