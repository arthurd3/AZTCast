package com.azt.streaming.transcoding.domain;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;

/**
 * Stage two of the pipeline: turn a media file into an HLS rendition ladder.
 *
 * <p>A port for the same reason as {@link com.azt.streaming.acquisition.domain.TorrentDownloader}:
 * the implementation shells out to ffmpeg, which is a minutes-long external process that is not
 * present in CI.
 *
 * <p>Two methods were dropped when this replaced {@code IStreamingService}, both with zero callers
 * and both broken: {@code getVideoPlaylist()} looked for {@code <hlsDir>/master.m3u8} without the
 * videoId segment, so it could never match the on-disk layout; and {@code startVideoProcessing()}
 * ignored the id it was handed, generated a new one, and self-invoked the async method — which
 * bypasses the Spring proxy, so the {@code @Async} never applied on that path.
 */
public interface MediaTranscoder {

    /**
     * Transcodes {@code inputFile} into an HLS ladder suited to it, under {@code videoId}.
     *
     * @param onProgress called with whole percentages as the encode advances. Invoked from the
     *     process's output-drain thread, so it must not block — see {@code FfmpegProgress}.
     * @return a future that completes when every rendition and the master playlist are written, or
     *     completes exceptionally if any rung fails. Callers must chain it — discarding it is how
     *     transcoding failures used to disappear silently.
     */
    CompletableFuture<Void> transcodeToHls(Path inputFile, String videoId, IntConsumer onProgress);

    /**
     * Rebuilds the manifests for a ladder whose media is already on disk and sound.
     *
     * <p>The repair path for the case that costs nothing to fix: segments that are all present and
     * correct, described by a master playlist no browser will accept. Re-encoding them would take
     * minutes to produce bit-identical output. This re-measures what is there and writes the
     * manifests again, in seconds.
     *
     * <p>It still needs the source file, because the audio and subtitle renditions are described by
     * decisions made from it — which language the audio is, what each subtitle track is called.
     *
     * @throws TranscodingException if the output cannot be measured or the rebuilt ladder would
     *     still not be publishable
     */
    CompletableFuture<Void> republish(Path inputFile, String videoId);

    /**
     * What is wrong with the ladder already published under {@code videoId}, if anything.
     *
     * <p>Asked of the transcoder because the transcoder is what published it: the shape of a ladder
     * — which playlists exist, what a variant is allowed to declare, what an fMP4 rung cannot be
     * decoded without — is knowledge that lives here and nowhere else.
     *
     * <p>Synchronous and cheap. It reads the playlists and stats the files they name; it opens no
     * media and starts no process.
     */
    LadderReport inspect(String videoId);
}
