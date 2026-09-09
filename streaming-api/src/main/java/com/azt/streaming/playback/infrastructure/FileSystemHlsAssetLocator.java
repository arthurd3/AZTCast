package com.azt.streaming.playback.infrastructure;

import com.azt.streaming.playback.domain.AssetNotFoundException;
import com.azt.streaming.playback.domain.HlsAsset;
import com.azt.streaming.playback.domain.HlsAssetLocator;
import com.azt.streaming.shared.storage.MediaStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Locates HLS assets on the local filesystem, via the containment-checked {@link MediaStorage}. */
@Slf4j
@Component
public class FileSystemHlsAssetLocator implements HlsAssetLocator {

    private final MediaStorage mediaStorage;

    public FileSystemHlsAssetLocator(MediaStorage mediaStorage) {
        this.mediaStorage = mediaStorage;
    }

    @Override
    public HlsAsset locate(String videoId, String fileName) {
        Path path = mediaStorage
                .resolveHlsAsset(videoId, fileName)
                .orElseThrow(() -> new AssetNotFoundException(videoId, fileName));
        return describe(videoId, fileName, path);
    }

    /**
     * Reads size and mtime once, here, so the response layer never touches the filesystem.
     *
     * <p>A file that vanishes between the containment check and this read is a 404, not a 500: it is
     * the same "not there" the caller already handles, and the media directory is a regenerable
     * cache that a reaper or a redeploy is allowed to empty underneath a request.
     */
    private static HlsAsset describe(String videoId, String fileName, Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return new HlsAsset(
                    videoId, fileName, path, attributes.size(), attributes.lastModifiedTime().toInstant());
        } catch (IOException e) {
            log.warn("HLS asset disappeared between lookup and read: {}/{}", videoId, fileName, e);
            throw new AssetNotFoundException(videoId, fileName);
        }
    }
}
