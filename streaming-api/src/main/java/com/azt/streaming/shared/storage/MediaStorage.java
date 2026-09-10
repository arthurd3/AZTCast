package com.azt.streaming.shared.storage;

import java.nio.file.Path;
import java.util.List;
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

    /**
     * Filename of the poster frame, written by transcoding and served by playback.
     *
     * <p>Optional: an encode that produced a playable ladder but no readable frame is still a
     * video, so nothing treats its absence as an error.
     */
    String POSTER = "poster.jpg";

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

    /**
     * Deletes a half-written HLS directory, and refuses to delete a finished one.
     *
     * <p>A failed encode used to leave its wreckage on disk until the reaper came for it a week
     * later: partial {@code .m4s} files, variant playlists for rungs that never finished, and no
     * {@code master.m3u8}. Invisible to the library, which lists only directories that have one, and
     * charged to the disk regardless.
     *
     * <p>The master playlist is also the guard. Its presence means the ladder finished, so anything
     * calling this on a ready video — a retry racing a completed encode, a mistaken id — is refused
     * rather than obeyed. Deletion here can only ever remove something nothing can play.
     *
     * @return whether anything was removed
     */
    boolean discardIncompleteHls(String videoId);

    /**
     * Directories of videos whose ladder is complete, i.e. whose master playlist exists.
     *
     * <p>The master playlist is written last, so its presence is the signal that the whole ladder is
     * ready. That makes this the durable answer to "what can be played right now" — job state is not:
     * it is per-process when Redis is off (the default) and expires under a TTL when it is on, while
     * the media outlives both.
     *
     * <p>Returns directories rather than ids because every caller needs to read inside them, and
     * handing back a name would only make them rebuild the path this class exists to own.
     */
    List<Path> listReadyVideoDirectories();
}
