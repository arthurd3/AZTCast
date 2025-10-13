package com.azt.streamingdata.service;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public interface IStreamingService {
    CompletableFuture<Void> processVideoAsync(Path inputFile, String videoId) throws IOException;
    Resource getVideoPlaylist();
    String startVideoProcessing(Path file, String videoId);
}
