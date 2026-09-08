package com.azt.streaming.ingestion.web;

import com.azt.streaming.ingestion.application.IngestionService;
import com.azt.streaming.ingestion.domain.StreamJob;
import com.azt.streaming.ingestion.web.dto.CreateStreamJobRequest;
import com.azt.streaming.ingestion.web.dto.StreamJobResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Starts ingestions and reports their progress. */
@RestController
@RequestMapping("/api/v1/videos")
public class IngestionController {

    private final IngestionService ingestionService;

    public IngestionController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping
    public ResponseEntity<StreamJobResponse> startIngestion(
            @Valid @RequestBody CreateStreamJobRequest request) {
        StreamJob job = ingestionService.startIngestion(request.magnetUrl());
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/videos/" + job.videoId()))
                .body(StreamJobResponse.from(job));
    }

    /**
     * Polling endpoint. Without it there was no way to distinguish "still transcoding" from "failed"
     * — both looked like a 404 on the master playlist.
     */
    @GetMapping("/{videoId}")
    public StreamJobResponse getJob(@PathVariable String videoId) {
        return StreamJobResponse.from(ingestionService.findJob(videoId));
    }
}
