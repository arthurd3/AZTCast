package com.azt.streaming.transcoding.domain;

/**
 * What to do when this ffmpeg build cannot turn the source's audio into AAC.
 *
 * <p>The situation is ordinary, not exotic: E-AC-3 is the audio of essentially every AMZN WEB-DL,
 * and a patent-free ffmpeg has no decoder for it. Before this existed the answer was "fail the
 * ingestion after the download", which is the one option nobody would have chosen deliberately.
 */
public enum UndecodableAudioPolicy {

    /**
     * Copy the track into the segments untouched when fMP4 can carry it, and drop it when it cannot.
     *
     * <p>The default, because it is the only option that keeps the video watchable everywhere and
     * the audio audible somewhere. A copied E-AC-3 track plays on Safari, iOS and tvOS and is silent
     * on Chrome — and the CODECS attribute says {@code ec-3}, so a player that cannot handle it
     * knows before it fetches a segment rather than after.
     */
    PASSTHROUGH,

    /** Produce a video-only ladder. Honest, universally playable, and silent. */
    DROP,

    /**
     * Fail the ingestion.
     *
     * <p>For an operator who would rather retry against a different release than serve a video
     * half the audience cannot hear. Still an improvement on the old behaviour: the decision is
     * made from the probe, so it costs milliseconds instead of a full ladder's timeout.
     */
    FAIL
}
