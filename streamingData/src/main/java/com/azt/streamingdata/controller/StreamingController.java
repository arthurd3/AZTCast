package com.azt.streamingdata.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/api/v1/stream")
@RequiredArgsConstructor
public class StreamingController {

    @Value("${dir.files.hls}")
    private String videoDir;

    @GetMapping("/{videoId}/master.m3u8")
    public ResponseEntity<Resource> getMasterPlaylist(@PathVariable String videoId) {
        Path masterPlaylistPath = Paths.get(videoDir, videoId, "master.m3u8");
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
        Path streamFilePath = Paths.get(videoDir, videoId, file);
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
