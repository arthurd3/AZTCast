package com.azt.streaming.playback.web;

import com.azt.streaming.playback.domain.HlsAssetLocator;
import com.azt.streaming.playback.domain.HlsMediaTypes;
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
 * to the master URL, so both mappings must keep their form.
 *
 * <p>The controller used to do the filesystem lookup and MIME resolution itself, without calling any
 * service — which is how the traversal hole got in. It delegates now.
 */
@RestController
@RequestMapping("/api/v1/stream")
public class PlaybackController {

    private final HlsAssetLocator assetLocator;

    public PlaybackController(HlsAssetLocator assetLocator) {
        this.assetLocator = assetLocator;
    }

    @GetMapping("/{videoId}/master.m3u8")
    public ResponseEntity<Resource> getMasterPlaylist(@PathVariable String videoId) {
        Resource playlist = assetLocator.locate(videoId, MediaStorage.MASTER_PLAYLIST);
        return ResponseEntity.ok().contentType(HlsMediaTypes.APPLE_MPEGURL).body(playlist);
    }

    @GetMapping("/{videoId}/{file}")
    public ResponseEntity<Resource> getStreamFile(
            @PathVariable String videoId, @PathVariable String file) {
        Resource asset = assetLocator.locate(videoId, file);
        return ResponseEntity.ok().contentType(HlsMediaTypes.forFileName(file)).body(asset);
    }
}
