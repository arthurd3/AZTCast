package com.azt.streaming.ingestion.application;

import com.azt.streaming.acquisition.domain.TorrentDownloader;
import com.azt.streaming.shared.storage.MediaStorage;
import com.azt.streaming.transcoding.domain.MediaTranscoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class IngestionService {

    private final MediaStorage mediaStorage;
    private final TorrentDownloader torrentDownloader;
    private final MediaTranscoder mediaTranscoder;

    public String startIngestion(final String magnetUrl) {

        final String videoId = UUID.randomUUID().toString();
        final Path downloadPath = mediaStorage.downloadDirectoryFor(videoId);

        log.info("Orchestrating new stream for videoId: {}", videoId);

        torrentDownloader.download(magnetUrl, downloadPath)
                .thenAccept(videoFile -> {
                    log.info("Download complete. Video file at {}. Starting HLS processing.", videoFile);

                    mediaTranscoder.transcodeToHls(videoFile, videoId);
                })
                .exceptionally(throwable -> {
                    log.error("Failed to process magnet link for videoId: {}", videoId, throwable);
                    return null;
                });

        log.info("Download and processing started in background for videoId: {}. Playlist will be available at /stream/{}", videoId, videoId);
        return videoId;
    }
}
