package com.azt.streaming.transcoding.domain;

/**
 * A rung as it was planned, paired with what the encoder actually produced for it.
 *
 * <p>These are two different things, and conflating them is the defect this type exists to prevent.
 * The ladder asks for Main profile; the encoder derives the level from the resolution and bitrate it
 * settled on, so one ladder yields level 4.0 at 1080p and 2.1 at 240p. The master playlist used to
 * advertise a single hardcoded {@code avc1.4d001f} for every rung, which was only ever correct for
 * 720p — see docs/troubleshooting-hls.md for why a wrong CODECS attribute is not cosmetic.
 *
 * <p>The bandwidths are measured for the same reason, and it is the copied rung that forced it. Its
 * declared bitrate was the source container's, which included an audio track that no longer travels
 * with it — so the one rung nothing rate-controlled was also the one whose advertised bandwidth was
 * furthest from the truth. Weighing the segments on disk answers it exactly, for every rung, and
 * costs a directory listing.
 *
 * @param rendition the planned rung
 * @param codecs the RFC 6381 string measured from the encoder's own output
 * @param averageVideoBps mean bits per second across the rung's segments, video only
 * @param peakVideoBps bits per second of its largest segment, video only
 */
public record EncodedRendition(HlsRendition rendition, String codecs, int averageVideoBps, int peakVideoBps) {

    /** A rung whose segments could not be weighed; falls back to what the ladder asked for. */
    public static EncodedRendition estimated(HlsRendition rendition, String codecs) {
        return new EncodedRendition(rendition, codecs, rendition.averageVideoBps(), rendition.peakVideoBps());
    }
}
