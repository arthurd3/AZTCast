package com.azt.streaming.playback.infrastructure;

import com.azt.streaming.playback.domain.AssetNotFoundException;
import com.azt.streaming.playback.domain.HlsAssetLocator;
import com.azt.streaming.shared.storage.MediaStorage;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/** Serves HLS assets from the local filesystem, via the containment-checked {@link MediaStorage}. */
@Component
public class FileSystemHlsAssetLocator implements HlsAssetLocator {

    private final MediaStorage mediaStorage;

    public FileSystemHlsAssetLocator(MediaStorage mediaStorage) {
        this.mediaStorage = mediaStorage;
    }

    @Override
    public Resource locate(String videoId, String fileName) {
        return mediaStorage
                .resolveHlsAsset(videoId, fileName)
                .<Resource>map(FileSystemResource::new)
                .orElseThrow(() -> new AssetNotFoundException(videoId, fileName));
    }
}
