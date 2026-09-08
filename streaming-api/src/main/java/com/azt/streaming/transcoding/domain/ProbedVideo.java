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
 * @param hasVideo whether a video stream is present at all
 * @param hasAudio whether an audio stream is present — a torrent may legitimately carry neither
 * @param videoProfile H.264 profile as ffprobe names it, e.g. {@code Main}
 * @param videoLevel H.264 {@code level_idc}, e.g. {@code 31} for level 3.1
 */
public record ProbedVideo(boolean hasVideo, boolean hasAudio, String videoProfile, int videoLevel) {

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
