package com.azt.streaming.transcoding.domain;

import java.util.Locale;
import java.util.Set;

/**
 * One subtitle track as ffprobe describes it.
 *
 * <p>Every one of these was dropped in silence until now. The command builder mapped {@code 0:v:0}
 * and {@code a:0} and nothing else, so the four tracks a typical release carries — original, SDH,
 * and two dubs — arrived on disk and were transcoded into nothing.
 *
 * @param index the ordinal among subtitle streams, i.e. the {@code N} in {@code -map 0:s:N}
 * @param codec {@code codec_name}, e.g. {@code subrip}
 * @param language container language tag, e.g. {@code por}; null when untagged
 * @param title container title tag, e.g. {@code Brazilian}; null when untagged
 * @param forced whether the container marks this as forced (translations of on-screen text only)
 * @param hearingImpaired whether the container marks this as SDH
 */
public record ProbedSubtitle(
        int index, String codec, String language, String title, boolean forced, boolean hearingImpaired) {

    /**
     * Subtitle codecs that are text and can therefore become WebVTT.
     *
     * <p>The rest — PGS and VobSub — are bitmaps. Converting those needs OCR, not a muxer, and
     * asking ffmpeg to do it fails the command rather than producing an empty file, which is why
     * this list gates the extraction instead of letting it be attempted and caught.
     */
    private static final Set<String> TEXT_CODECS =
            Set.of("subrip", "srt", "ass", "ssa", "mov_text", "webvtt", "text", "subviewer", "microdvd");

    public boolean isText() {
        return codec != null && TEXT_CODECS.contains(codec.toLowerCase(Locale.ROOT));
    }

    /**
     * A filename-safe stem, unique across the tracks of one source.
     *
     * <p>The index is part of it deliberately. A release carrying two English tracks that differ
     * only by a title the container did not set would otherwise produce one file twice, and the
     * second extraction would overwrite the first.
     */
    public String fileStem() {
        StringBuilder stem = new StringBuilder("sub_").append(LanguageTag.bcp47(language));
        if (hearingImpaired) {
            stem.append("-sdh");
        }
        if (forced) {
            stem.append("-forced");
        }
        return stem.append('-').append(index).toString();
    }

    /** What the subtitle picker shows, e.g. {@code Portuguese (Brazilian)} or {@code English (SDH)}. */
    public String label() {
        String base = LanguageTag.displayName(language);
        String qualifier = qualifier();
        return qualifier == null ? base : base + " (" + qualifier + ")";
    }

    private String qualifier() {
        if (title != null && !title.isBlank()) {
            return title.trim();
        }
        if (hearingImpaired) {
            return "SDH";
        }
        return forced ? "Forced" : null;
    }
}
