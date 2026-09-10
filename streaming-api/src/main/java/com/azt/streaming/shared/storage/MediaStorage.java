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
     * The source file still on disk for {@code videoId}, if the reaper has not taken it yet.
     *
     * <p>The largest regular file under the download directory. Deliberately simpler than
     * {@code VideoFileLocator}, which walks an entire torrent and has to filter by extension to
     * avoid picking up a sample or an NFO: by the time anything asks this, {@code download-video-only}
     * has already reduced the directory to the one file that was wanted. Picking wrongly is not
     * dangerous either — ffprobe rejects a non-media file and the caller falls back to re-fetching.
     *
     * <p>Empty means the source is gone, which is the normal state for any video whose transcode
     * finished: the download is discarded as soon as the ladder it produced is verified.
     */
    Optional<Path> existingDownload(String videoId);

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
     * Deletes the raw torrent downloaded for {@code videoId}.
     *
     * <p>Called once a transcode is verified, and by the reaper for downloads no transcode ever
     * claimed. Unlike {@link #discardIncompleteHls} there is no sentinel to check first, because
     * there is no state of this directory worth protecting: it is a second full copy of a video that
     * is not what anyone watches, nothing seeds it, and repair goes back to the recorded magnet
     * rather than to these bytes.
     *
     * @return whether anything was removed
     */
    boolean discardDownload(String videoId);

    /**
     * Deletes the HLS ladder for {@code videoId}, finished or not.
     *
     * <p>The one place in the application that removes a watchable video, and it exists only to
     * serve a person who asked for exactly that. Nothing schedules it. {@link #discardIncompleteHls}
     * remains the safe, sentinel-guarded call for the pipeline's own cleanup; this one deliberately
     * has no guard, so it must never be reached except from an explicit request.
     *
     * @return whether anything was removed
     */
    boolean discardHls(String videoId);

    /**
     * How much room the volume holding the media roots has.
     *
     * <p>Empty when the filesystem cannot be queried. That is deliberately distinct from zero free —
     * a caller deciding whether to refuse work must be able to tell "no room" from "no answer", and
     * refusing an ingestion because a stat call failed would be the wrong way to be careful.
     */
    Optional<VolumeSpace> volumeSpace();

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
