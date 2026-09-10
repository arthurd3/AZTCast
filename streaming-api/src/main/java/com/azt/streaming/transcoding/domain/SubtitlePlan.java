package com.azt.streaming.transcoding.domain;

/**
 * One subtitle track, as it will be published.
 *
 * <p>Deliberately not built into the ladder command. {@code -var_stream_map} accepts an
 * {@code sgroup:} key, but using it makes one hlsenc instance emit fMP4 video segments and WebVTT
 * text segments at the same time, which is the least exercised corner of that muxer. Extracting each
 * track with its own short invocation costs one extra pass over a text stream — measured in
 * kilobytes — and keeps the one command that matters unchanged.
 *
 * @param sourceIndex the {@code N} in {@code -map 0:s:N}
 * @param name filename stem, unique within the video's folder
 * @param label what a track picker shows
 * @param language RFC 5646 tag for the playlist's {@code LANGUAGE} attribute
 * @param forced whether this track translates on-screen text only
 * @param hearingImpaired whether this track is SDH
 */
public record SubtitlePlan(
        int sourceIndex, String name, String label, String language, boolean forced, boolean hearingImpaired) {

    /** The {@code GROUP-ID} every variant points at with {@code SUBTITLES="…"}. */
    public static final String GROUP_ID = "subs";

    public static SubtitlePlan from(ProbedSubtitle track) {
        return new SubtitlePlan(
                track.index(),
                track.fileStem(),
                track.label(),
                LanguageTag.bcp47(track.language()),
                track.forced(),
                track.hearingImpaired());
    }

    /** The WebVTT file itself, which doubles as the single segment of its playlist. */
    public String vttFileName() {
        return name + ".vtt";
    }

    public String playlistFileName() {
        return name + ".m3u8";
    }
}
