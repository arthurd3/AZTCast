package com.azt.streaming.controller;

import com.azt.streaming.shared.config.StreamingProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;

@RestController
@RequestMapping("/api/v1/stream")
@RequiredArgsConstructor
public class StreamingController {

    private final StreamingProperties properties;

    @GetMapping("/{videoId}/master.m3u8")
    public ResponseEntity<Resource> getMasterPlaylist(@PathVariable String videoId) {
        Path masterPlaylistPath = properties.storage().hlsDir().resolve(videoId).resolve("master.m3u8");
        Resource resource = new FileSystemResource(masterPlaylistPath);

        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"))
                .body(resource);
    }

    @GetMapping("/{videoId}/{file}")
    public ResponseEntity<Resource> getStreamFile(@PathVariable String videoId, @PathVariable String file) {
        Path streamFilePath = properties.storage().hlsDir().resolve(videoId).resolve(file);
        Resource resource = new FileSystemResource(streamFilePath);
        
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        
        MediaType contentType;
        if (file.endsWith(".ts")) {
            contentType = MediaType.valueOf("video/mp2t");
        } else if (file.endsWith(".m3u8")) {
            contentType = MediaType.parseMediaType("application/vnd.apple.mpegurl");
        } else {
            contentType = MediaType.APPLICATION_OCTET_STREAM;
        }
        
        return ResponseEntity.ok()
                .contentType(contentType)
                .body(resource);
    }
}
