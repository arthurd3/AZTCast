package com.azt.streaming.transcoding.domain;

/**
 * A rung, and how ffmpeg should produce it.
 *
 * <p>The boolean is the whole point. A ladder used to be a list of things to encode; it is now a
 * list in which the top entry is usually the source itself, remuxed rather than re-encoded. That
 * distinction has to survive as far as the command builder, because a copied stream cannot be sent
 * through a filter graph — it is mapped straight off the input.
 *
 * <p>There was a second boolean, {@code copyAudio}, until the ladder moved to one shared audio
 * rendition. Deciding audio per rung was never meaningful — every rung mapped the same {@code a:0}
 * — and it is what let a copied rung's zero audio bitrate reach ffmpeg as {@code -b:a:0 0k}.
 *
 * @param rendition the rung, with the name, dimensions and bitrate the playlist will advertise
 * @param copyVideo copy the source video stream instead of encoding it
 */
public record PlannedRendition(HlsRendition rendition, boolean copyVideo) {

    public static PlannedRendition encoded(HlsRendition rendition) {
        return new PlannedRendition(rendition, false);
    }
}
