package com.azt.streaming.transcoding.domain;

/**
 * The shared audio rendition, paired with what it actually weighed once written.
 *
 * <p>A copied track has no target bitrate to advertise — nothing rate-controlled it — so the only
 * honest figure is the one measured off the segments. An encoded track has a target, and measuring
 * it anyway costs a directory listing and catches the case where the encoder did not hit it, which
 * for {@code libopenh264} is the normal case rather than the exception.
 *
 * @param plan what was asked for
 * @param averageBps mean bits per second across the rendition's segments
 * @param peakBps bits per second of its largest segment
 */
public record EncodedAudio(AudioPlan plan, int averageBps, int peakBps) {

    /** No audio rendition at all. */
    public static EncodedAudio none() {
        return new EncodedAudio(AudioPlan.none(null), 0, 0);
    }

    public boolean present() {
        return plan.present();
    }
}
