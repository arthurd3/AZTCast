package com.azt.streaming.service;

import com.azt.streaming.controller.request.MagnetUrl;
import com.azt.streaming.shared.config.StreamingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class MagnetStreamingOrchestrator {

    private final StreamingProperties properties;
    private final ITorrentService torrentService;
    private final IStreamingService streamingService;

    public String processMagnetLink(final MagnetUrl torrentLink)  {

        final String videoId = UUID.randomUUID().toString();
        final Path downloadPath = properties.storage().downloadsDir().resolve(videoId);

        log.info("Orchestrating new stream for videoId: {}", videoId);

        torrentService.downloadTorrentLink(torrentLink.magnetUrl(), downloadPath)
                .thenAccept(videoFile -> {
                    log.info("Download complete. Video file at {}. Starting HLS processing.", videoFile);

                    try {
                        streamingService.processVideoAsync(videoFile, videoId);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }

                })
                .exceptionally(throwable -> {
                    log.error("Failed to process magnet link for videoId: {}", videoId, throwable);
                    return null;
                });

        log.info("Download and processing started in background for videoId: {}. Playlist will be available at /stream/{}", videoId, videoId);
        return videoId;
    }
}
