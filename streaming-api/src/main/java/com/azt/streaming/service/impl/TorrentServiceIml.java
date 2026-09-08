package com.azt.streaming.service.impl;

import bt.Bt;
import bt.data.Storage;
import bt.data.file.FileSystemStorage;
import bt.dht.DHTConfig;
import bt.dht.DHTModule;
import bt.runtime.BtClient;
import bt.runtime.Config;
import bt.torrent.selector.SequentialSelector;
import com.azt.streaming.service.ITorrentService;
import com.google.inject.Module;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
public class TorrentServiceIml implements ITorrentService {


    private Config config = new Config() {
        @Override
        public int getNumOfHashingThreads() {
            return Runtime.getRuntime().availableProcessors() * 2;
        }
    };

    private Module dhtModule = new DHTModule(new DHTConfig() {
        @Override
        public boolean shouldUseRouterBootstrap() {
            return true;
        }
    });

    // get download directory
    public CompletableFuture<Path> downloadTorrentLink(final String magnetLink , Path downloadDirectory) {

        CompletableFuture<Path> downloadFuture = new CompletableFuture<>();

        // create file system based backend for torrent data
        Storage storage = new FileSystemStorage(downloadDirectory);

        // create client with a private runtime
        BtClient client = Bt.client()
                .config(config)
                .storage(storage)
                .magnet(magnetLink)
                .autoLoadModules()
                .module(dhtModule)
                .selector(SequentialSelector.sequential()) // <-- FORCE SEQUENTIAL DOWNLOAD
                .stopWhenDownloaded()
                .build();

        // launch
        client.startAsync(state -> {
            if (state.getPiecesRemaining() == 0) {
                log.info("Download do magnet {} concluído!", magnetLink);
                findVideoFile(downloadDirectory)
                .ifPresentOrElse(
                        downloadFuture::complete,
                        () -> downloadFuture.completeExceptionally(new RuntimeException("No video file found in torrent"))
                );

            } else {
                log.info("Progresso: {}% para o magnet {}", calculateProgress(state), magnetLink);
            }
        }, 1000);

        log.info("Iniciando download em segundo plano para o magnet: {}", magnetLink);

        return downloadFuture;
    }

    private double calculateProgress(bt.torrent.TorrentSessionState state) {
        int totalPieces = state.getPiecesTotal();
        int completedPieces = totalPieces - state.getPiecesRemaining();
        return (double) completedPieces / totalPieces * 100.0;
    }


    private Optional<Path> findVideoFile(Path directory) {
        File dir = directory.toFile();
        if (!dir.isDirectory()) {
            return Optional.empty();
        }

        File[] files = dir.listFiles();
        if (files == null) {
            return Optional.empty();
        }

        File largestVideoFile = null;
        long maxSize = -1;

        for (File file : files) {
            if (file.isFile() && isVideoFile(file.getName()) && file.length() > maxSize) {
                maxSize = file.length();
                largestVideoFile = file;
            }
        }
        return Optional.ofNullable(largestVideoFile).map(File::toPath);
    }

    private boolean isVideoFile(String fileName) {
        String lowerName = fileName.toLowerCase();
        return lowerName.endsWith(".mp4") || lowerName.endsWith(".mkv") || lowerName.endsWith(".avi") || lowerName.endsWith(".mov");
    }



}