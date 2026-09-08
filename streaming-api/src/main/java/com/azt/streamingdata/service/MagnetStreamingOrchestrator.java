package com.azt.streamingdata.service;

import com.azt.streamingdata.controller.request.MagnetUrl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class MagnetStreamingOrchestrator {

    @Value("${dir.files.download}")
    private String DOWNLOAD_DIR;

    private final ITorrentService torrentService;
    private final IStreamingService streamingService;

    public String processMagnetLink(final MagnetUrl torrentLink)  {

        final String videoId = UUID.randomUUID().toString();
        final Path downloadPath = Paths.get(DOWNLOAD_DIR, videoId);

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
