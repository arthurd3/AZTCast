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
}
