package com.azt.streaming.transcoding.domain;

import java.util.Locale;

/**
 * What a rung <em>this service produced</em> turned out to be, measured back off the disk.
 *
 * <p>The distinction from what was asked for is the point. The encoder is asked for Main@3.1, but
 * what it produces depends on the build and on the content: {@code libopenh264} silently falls back
 * to Constrained Baseline when no profile is requested, and every encoder derives the level from the
 * resolution and bitrate it ended up with, so a 240p rung comes out at level 2.1 no matter what the
 * command said.
 *
 * <p>It carries only the fields a CODECS string needs. Describing a <em>source</em> — an arbitrary
 * file out of a swarm, whose codecs, channel counts and subtitle tracks are all decisions waiting to
 * be made — is {@link ProbedSource}'s job. The two used to be one ten-component record whose javadoc
 * had to explain which half of itself to ignore on which call.
 *
 * @param hasVideo whether a video stream is present at all
 * @param hasAudio whether the variant carries audio of its own. False for every rung since the
 *     ladder moved to a shared audio group, and still read, because a build that ever muxes audio
 *     back in must not silently stop advertising it.
 * @param videoProfile H.264 profile as ffprobe names it, e.g. {@code Main}
 * @param videoLevel H.264 {@code level_idc}, e.g. {@code 31} for level 3.1
 */
public record ProbedVideo(boolean hasVideo, boolean hasAudio, String videoProfile, int videoLevel) {

    /** A rung measured for its CODECS attribute. */
    public static ProbedVideo measured(boolean hasAudio, String videoProfile, int videoLevel) {
        return new ProbedVideo(true, hasAudio, videoProfile, videoLevel);
    }

    /**
     * AAC-LC. Constant because every encoder in the configured preference list produces AAC-LC; if a
     * non-AAC audio encoder ever becomes selectable this has to be measured like the video half.
     */
    public static final String AAC_LC = "mp4a.40.2";

    /** What the master playlist advertised for every rung before any of this was measured. */
    public static final String FALLBACK_CODECS = "avc1.4d001f";

    /**
     * The RFC 6381 codec string for this stream: {@code avc1.PPCCLL}, where PP is the profile_idc,
     * CC the constraint flags and LL the level_idc, each two hex digits.
     *
     * <p>This is what a player reads to decide whether it can play a rung before fetching a byte of
     * it. Getting it wrong in either direction is a real failure: overstate the level and a device
     * rejects a rendition it could have played; understate it and the device accepts one it cannot.
     *
     * <p>Audio is not appended here any more. It lives in its own rendition group now, and the
     * master playlist is what joins the two — a variant's CODECS has to name the combination the
     * player will actually assemble, which is knowledge this type does not have.
     */
    public String codecs() {
        return "avc1.%s%02x".formatted(profileAndConstraints(), videoLevel);
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
