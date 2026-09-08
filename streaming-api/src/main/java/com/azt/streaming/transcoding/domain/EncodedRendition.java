package com.azt.streaming.transcoding.domain;

/**
 * A rung as it was configured, paired with what the encoder actually produced for it.
 *
 * <p>These are two different things, and conflating them is the defect this type exists to prevent.
 * The ladder asks for Main profile; the encoder derives the level from the resolution and bitrate it
 * settled on, so one ladder yields level 4.0 at 1080p and 2.1 at 240p. The master playlist used to
 * advertise a single hardcoded {@code avc1.4d001f} for every rung, which was only ever correct for
 * 720p — see docs/troubleshooting-hls.md for why a wrong CODECS attribute is not cosmetic.
 *
 * @param rendition the configured rung
 * @param codecs the RFC 6381 string measured from the encoder's own output
 */
public record EncodedRendition(HlsRendition rendition, String codecs) {}
