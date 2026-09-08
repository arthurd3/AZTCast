package com.azt.streaming.shared.storage;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The single owner of "where media lives on disk, and is this path inside the root".
 *
 * <p>Centralising that is what closes the traversal hole: before, the playback controller built
 * {@code Paths.get(videoDir, videoId, file)} from unsanitised path variables with only an
 * {@code exists()} check, and the transcoder resolved its own output paths separately. Two places
 * that construct filesystem paths from request data are two places that can get containment wrong.
 * There is one now.
 */
public interface MediaStorage {

    /** Filename of the HLS master playlist, written by transcoding and served by playback. */
    String MASTER_PLAYLIST = "master.m3u8";

    /** Directory a torrent for {@code videoId} downloads into. Created on demand. */
    Path downloadDirectoryFor(String videoId);

    /** Directory holding the HLS ladder for {@code videoId}. Created on demand. */
    Path hlsDirectoryFor(String videoId);

    /**
     * Resolves an HLS asset, or returns empty if it does not exist or would escape the HLS root.
     *
     * @param videoId the video's identifier, from the request
     * @param fileName a playlist or segment name, from the request
     */
    Optional<Path> resolveHlsAsset(String videoId, String fileName);
}
