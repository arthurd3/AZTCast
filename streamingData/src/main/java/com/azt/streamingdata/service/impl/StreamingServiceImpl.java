package com.azt.streamingdata.service.impl;

import com.azt.streamingdata.model.Video;
import com.azt.streamingdata.service.IStreamingService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
public class StreamingServiceImpl implements IStreamingService {

    @Value("${dir.files.hls}")
    private String HLS_DIR;

    @PostConstruct
    public void init(){
        File file = new File(HLS_DIR);
        if(!file.exists()){
            file.mkdir();
            System.out.println("Directory created");
        }else{
            System.out.println("Directory already exists");
        }
    }

    @Override
    public String startVideoProcessing(Path file, String id) {
        String videoId = UUID.randomUUID().toString();
        System.out.println("Before async call");
        processVideoAsync(file, videoId);
        System.out.println("After async call - Video ID: " + videoId);
        return videoId;
    }

    private String calculateMaxrate(String bitrate) {
        int br = Integer.parseInt(bitrate.replace("k", ""));
        return (int)(br * 1.07) + "k"; // ~7% overhead
    }

    private String calculateBufsize(String bitrate) {
        int br = Integer.parseInt(bitrate.replace("k", ""));
        return (int)(br * 1.5) + "k"; // buffer ~1.5x bitrate
    }

    @Override
    public Resource getVideoPlaylist() {
        File masterPlaylistFile = new File(HLS_DIR + File.separator + "master.m3u8");
        if (!masterPlaylistFile.exists()) {
            throw new RuntimeException("Master playlist not found");
        }
        return new FileSystemResource(masterPlaylistFile);
    }


    @Override
    @Async
    public CompletableFuture<Void> processVideoAsync(Path inputFile, String videoId) {
        log.info("Starting HLS processing for videoId: {} from file: {}", videoId, inputFile);

        try {
            Path hlsRoot = Paths.get(HLS_DIR);
            Path videoFolder = hlsRoot.resolve(videoId);
            Files.createDirectories(videoFolder);

            // Define resolutions and settings
            String[] resolutions = {"720", "240"};
            String[] dimensions = {"1280x720", "426x240"};
            String[] bitrates = {"3000k", "800k"};

            for (int i = 0; i < resolutions.length; i++) {
                String res = resolutions[i];
                String dimension = dimensions[i];
                String bitrate = bitrates[i];
                String outputPlaylist = videoFolder.resolve(res + "p.m3u8").toString();
                String segmentPattern = videoFolder.resolve(res + "p_%03d.ts").toString();

                List<String> ffmpegCommand = Arrays.asList(
                    "ffmpeg",
                    "-i", inputFile.toString(),
                    "-c:v", "libx264",
                    "-profile:v", "main",
                    "-level", "3.1",
                    "-preset", "veryfast",
                    "-s", dimension,
                    "-b:v", bitrate,
                    "-maxrate", calculateMaxrate(bitrate),
                    "-bufsize", calculateBufsize(bitrate),
                    "-c:a", "aac",
                    "-b:a", "128k",
                    "-ac", "2",
                    "-ar", "48000",
                    "-f", "hls",
                    "-hls_time", "4",
                    "-hls_playlist_type", "vod",
                    "-hls_segment_filename", segmentPattern,
                    outputPlaylist
                );

                System.out.println("Processing " + res + "p resolution...");
                System.out.println("Running FFmpeg command: " + String.join(" ", ffmpegCommand));

                ProcessBuilder pb = new ProcessBuilder(ffmpegCommand);
                pb.redirectErrorStream(true);
                Process process = pb.start();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("FFmpeg output: " + line);
                    }
                }

                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    throw new RuntimeException("FFmpeg failed for resolution " + res + "p with exit code " + exitCode);
                }
            }

            Path masterPath = videoFolder.resolve("master.m3u8");
            StringBuilder masterContent = new StringBuilder();
            masterContent.append("#EXTM3U\n");
            masterContent.append("#EXT-X-VERSION:3\n");
            
            // Generate master playlist only for processed resolutions
            for (int i = 0; i < resolutions.length; i++) {
                String res = resolutions[i];
                String dimension = dimensions[i];
                String bitrate = bitrates[i].replace("k", "000"); // Convert to actual bandwidth
                
                masterContent.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(bitrate)
                           .append(",RESOLUTION=").append(dimension).append("\n");
                masterContent.append(res).append("p.m3u8\n");
            }

            Files.writeString(masterPath, masterContent.toString());

            log.info("HLS processing completed for videoId: {}", videoId);

        } catch (IOException | InterruptedException e) {
            log.error("Error processing video file for videoId: {}", videoId, e);
            throw new RuntimeException(e);
        }

        return CompletableFuture.completedFuture(null);
    }



}
