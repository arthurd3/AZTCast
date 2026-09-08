package com.azt.streaming.playback.domain;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

/**
 * An HLS file that exists on disk and has already been proven to sit inside the media root.
 *
 * <p>This carries a {@link Path} rather than a Spring {@code Resource} on purpose. Playback now has
 * two ways to answer a request — write the bytes itself, or hand nginx the filename and let
 * {@code sendfile} do it — and only one of them involves a {@code Resource} at all. Modelling the
 * asset as "a file, its size and its mtime" keeps that choice in the web layer where it belongs.
 *
 * @param videoId the video's identifier, already containment-checked
 * @param fileName a playlist, segment or init-segment name, already containment-checked
 * @param path absolute path to the file
 * @param sizeBytes file size, read once at lookup rather than per header
 * @param lastModified file mtime, used as a cache validator
 */
public record HlsAsset(String videoId, String fileName, Path path, long sizeBytes, Instant lastModified) {

    /**
     * Whether this file can be cached forever.
     *
     * <p>True for everything except playlists. A {@code videoId} is a fresh UUID per ingestion, so a
     * segment or init segment under it is written exactly once and never rewritten in place — the
     * URL identifies those bytes for good, which is precisely what {@code Cache-Control: immutable}
     * asserts. Playlists are excluded because a re-transcode rewrites them under names the ladder
     * reuses ({@code 720p.m3u8}, {@code master.m3u8}).
     */
    public boolean isImmutable() {
        return !fileName.toLowerCase(Locale.ROOT).endsWith(".m3u8");
    }

    /**
     * Validator built from mtime and size — deliberately byte-identical to the one nginx generates
     * for a static file: {@code "hex(mtime seconds)-hex(size)"}.
     *
     * <p>Matching the format exactly is what makes {@code offload-enabled} a safe flag to flip. The
     * two modes are answered by different servers, so if they spelled the validator differently,
     * every client cache entry would silently stop revalidating the moment the flag changed and
     * every segment would be re-downloaded in full. Same file, same ETag, either way.
     */
    public String eTag() {
        return "\"%x-%x\"".formatted(lastModified.getEpochSecond(), sizeBytes);
    }
}
