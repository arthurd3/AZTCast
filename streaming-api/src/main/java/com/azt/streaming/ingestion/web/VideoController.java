package com.azt.streaming.ingestion.web;

import com.azt.streaming.ingestion.application.IngestionService;
import com.azt.streaming.ingestion.web.dto.CreateStreamJobRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/video")
@RequiredArgsConstructor
public class VideoController {

    private final IngestionService ingestionService;


    @PostMapping("/download")
    public ResponseEntity<String> downloadTorrentLink(@RequestBody final CreateStreamJobRequest request) {

        String videoId = ingestionService.startIngestion(request.magnetUrl());

        String streamUrl = "/api/v1/stream/" + videoId + "/master.m3u8";
        String responseBody = "Download iniciado. O stream estará disponível em: " + streamUrl;

        return ResponseEntity.accepted().body(responseBody);
    }



}
