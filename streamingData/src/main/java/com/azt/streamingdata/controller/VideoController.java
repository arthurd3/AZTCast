package com.azt.streamingdata.controller;

import com.azt.streamingdata.controller.request.MagnetUrl;
import com.azt.streamingdata.model.TorrentLink;
import com.azt.streamingdata.model.VideoInfo;
import com.azt.streamingdata.service.ITorrentService;
import com.azt.streamingdata.service.MagnetStreamingOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
