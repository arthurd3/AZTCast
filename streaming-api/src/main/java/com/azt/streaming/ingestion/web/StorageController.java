package com.azt.streaming.ingestion.web;

import com.azt.streaming.ingestion.web.dto.StorageResponse;
import com.azt.streaming.shared.config.StreamingProperties;
import com.azt.streaming.shared.storage.CatalogEntry;
import com.azt.streaming.shared.storage.MediaStorage;
import com.azt.streaming.shared.storage.VideoCatalog;
import com.azt.streaming.shared.storage.VolumeSpace;
import java.util.List;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the library is costing, and what is left.
 *
 * <p>A controller of its own rather than another route on {@link IngestionController}, whose class
 * mapping is {@code /api/v1/videos}: this answers about the disk, not about a video, and nesting it
 * under a collection it is not part of would be the wrong shape for the sake of one fewer file.
 */
@RestController
public class StorageController {

    private final MediaStorage mediaStorage;
    private final VideoCatalog videoCatalog;
    private final StreamingProperties properties;

    public StorageController(
            MediaStorage mediaStorage, VideoCatalog videoCatalog, StreamingProperties properties) {
        this.mediaStorage = mediaStorage;
        this.videoCatalog = videoCatalog;
        this.properties = properties;
    }

    /**
     * {@code no-store} for the same reason the listing carries it: an ingestion finishing changes
     * this, and a cached copy would keep reporting room that has since been spent.
     */
    @GetMapping("/api/v1/storage")
    public ResponseEntity<StorageResponse> storage() {
        Optional<VolumeSpace> space = mediaStorage.volumeSpace();
        List<CatalogEntry> videos = videoCatalog.list();
        StorageResponse body = new StorageResponse(
                space.map(VolumeSpace::usableBytes).orElse(null),
                space.map(VolumeSpace::totalBytes).orElse(null),
                videos.stream().mapToLong(CatalogEntry::sizeBytes).sum(),
                videos.size(),
                properties.storage().minFreeSpace().toBytes());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
