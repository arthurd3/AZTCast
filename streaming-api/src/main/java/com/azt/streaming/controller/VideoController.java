package com.azt.streaming.controller;

import com.azt.streaming.controller.request.MagnetUrl;
import com.azt.streaming.service.MagnetStreamingOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/video")
@RequiredArgsConstructor
public class VideoController {

    private final MagnetStreamingOrchestrator magnetStreamingOrchestrator;


    @PostMapping("/download")
    public ResponseEntity<String> downloadTorrentLink(@RequestBody final MagnetUrl torrentLink){

        String videoId = magnetStreamingOrchestrator.processMagnetLink(torrentLink);

        String streamUrl = "/api/v1/stream/" + videoId + "/master.m3u8";
        String responseBody = "Download iniciado. O stream estará disponível em: " + streamUrl;

        return ResponseEntity.accepted().body(responseBody);
    }



}
