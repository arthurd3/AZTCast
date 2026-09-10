package com.azt.streaming.transcoding.domain;

/**
 * A rung, and how ffmpeg should produce it.
 *
 * <p>The two booleans are the whole point. A ladder used to be a list of things to encode; it is now
 * a list in which the top entry is usually the source itself, remuxed rather than re-encoded. That
 * distinction has to survive as far as the command builder, because a copied stream cannot be sent
 * through a filter graph — it is mapped straight off the input.
 *
 * @param rendition the rung, with the name, dimensions and bitrates the playlist will advertise
 * @param copyVideo copy the source video stream instead of encoding it
 * @param copyAudio copy the source audio stream instead of encoding it
 */
public record PlannedRendition(HlsRendition rendition, boolean copyVideo, boolean copyAudio) {

    public static PlannedRendition encoded(HlsRendition rendition) {
        return new PlannedRendition(rendition, false, false);
    }
}
