package com.azt.streaming.playback.web;

import com.azt.streaming.playback.domain.HlsAssetLocator;
import com.azt.streaming.shared.storage.MediaStorage;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the HLS ladder.
 *
 * <p>This path shape is a frozen contract: hls.js resolves variant playlists and segments relative
 * to the master URL, so both mappings must keep their form. Note that {@code {file}} is a single
 * path segment — an ffmpeg ladder that writes {@code stream_0/playlist.m3u8} would not be reachable
 * through this controller at all.
 *
 * <p>The controller used to do the filesystem lookup and MIME resolution itself, without calling any
 * service — which is how the traversal hole got in. It delegates now: {@link HlsAssetLocator} finds
 * the file, {@link HlsResponseFactory} decides whether this process or nginx writes its bytes.
 */
@RestController
@RequestMapping("/api/v1/stream")
public class PlaybackController {

    private final HlsAssetLocator assetLocator;
    private final HlsResponseFactory responses;

    public PlaybackController(HlsAssetLocator assetLocator, HlsResponseFactory responses) {
        this.assetLocator = assetLocator;
        this.responses = responses;
    }

    @GetMapping("/{videoId}/master.m3u8")
    public ResponseEntity<Resource> getMasterPlaylist(@PathVariable String videoId) {
        return responses.toResponse(assetLocator.locate(videoId, MediaStorage.MASTER_PLAYLIST));
    }

    @GetMapping("/{videoId}/{file}")
    public ResponseEntity<Resource> getStreamFile(
            @PathVariable String videoId, @PathVariable String file) {
        return responses.toResponse(assetLocator.locate(videoId, file));
    }
}
