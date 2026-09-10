package com.azt.streaming.playback.domain;

import java.util.Locale;
import org.springframework.http.MediaType;

/**
 * Content types for HLS assets.
 *
 * <p>Serving a segment with the wrong type is one of the documented causes of the
 * {@code bufferAppend} failures this project chased — see docs/troubleshooting-hls.md. The previous
 * inline switch handled only {@code .m3u8} and {@code .ts}; fragmented-MP4 output fell through to
 * {@code application/octet-stream}, which browsers refuse to append to a media source.
 */
public final class HlsMediaTypes {

    public static final MediaType APPLE_MPEGURL = MediaType.parseMediaType("application/vnd.apple.mpegurl");
    private static final MediaType MP2T = MediaType.parseMediaType("video/mp2t");
    private static final MediaType ISO_SEGMENT = MediaType.parseMediaType("video/iso.segment");
    private static final MediaType MP4 = MediaType.parseMediaType("video/mp4");
    private static final MediaType WEBVTT = MediaType.parseMediaType("text/vtt");

    private HlsMediaTypes() {}

    public static MediaType forFileName(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".m3u8")) {
            return APPLE_MPEGURL;
        }
        if (name.endsWith(".ts")) {
            return MP2T;
        }
        if (name.endsWith(".m4s")) {
            return ISO_SEGMENT;
        }
        if (name.endsWith(".mp4")) {
            return MP4;
        }
        // A subtitle track. Browsers refuse to parse a cue file served as anything but text/vtt,
        // and the failure is silent: the track appears in the picker and shows nothing.
        if (name.endsWith(".vtt")) {
            return WEBVTT;
        }
        // The poster frame. Not an HLS asset, but it lives in the same directory and is served by
        // the same mapping, and a browser will not paint an <img> it was handed as octet-stream.
        if (name.endsWith(".jpg")) {
            return MediaType.IMAGE_JPEG;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
