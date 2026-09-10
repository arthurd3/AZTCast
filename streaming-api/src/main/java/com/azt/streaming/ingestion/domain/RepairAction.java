package com.azt.streaming.ingestion.domain;

/**
 * What repairing a video turned out to require.
 *
 * <p>The point of naming these is that they cost wildly different amounts, and a caller deserves to
 * know which one it just started. Rebuilding a manifest finishes while the request is still open;
 * re-fetching a torrent does not.
 */
public enum RepairAction {

    /** The ladder is sound and a browser can play it. Nothing was touched. */
    NOTHING_TO_DO,

    /**
     * The media is intact and the manifests were not.
     *
     * <p>The cheap case, and the one that fixes a whole library published by a version of this
     * service that advertised a codec the browser refused: the segments are bit-for-bit correct, so
     * only the playlists are rewritten. Seconds, and no encoder runs.
     */
    MANIFESTS_REBUILT,

    /**
     * The ladder was intact, and this host can now produce better audio than it carries.
     *
     * <p>What that means in practice: the video was published on a build with no decoder for its
     * audio, so the track was copied through untouched and only Apple's platforms could play it.
     * A decoder has been installed since. Only the audio rendition is re-encoded — the video rungs
     * are already correct, and re-encoding them would spend minutes producing identical bytes.
     */
    AUDIO_REBUILT,

    /** Segments were missing or empty; the retained download was transcoded again. */
    RETRANSCODED,

    /** The download was gone too, so the torrent is being fetched again from the recorded magnet. */
    REFETCHED
}
