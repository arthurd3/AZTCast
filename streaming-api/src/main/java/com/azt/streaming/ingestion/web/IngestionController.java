package com.azt.streaming.ingestion.web;

import com.azt.streaming.ingestion.application.IngestionService;
import com.azt.streaming.ingestion.domain.StreamJob;
import com.azt.streaming.ingestion.web.dto.CreateStreamJobRequest;
import com.azt.streaming.ingestion.web.dto.StreamJobResponse;
import com.azt.streaming.ingestion.web.dto.VideoSummaryResponse;
import com.azt.streaming.shared.storage.VideoCatalog;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Starts ingestions, reports their progress, and lists what finished. */
@RestController
@RequestMapping("/api/v1/videos")
public class IngestionController {

    private final IngestionService ingestionService;
    private final VideoCatalog videoCatalog;

    public IngestionController(IngestionService ingestionService, VideoCatalog videoCatalog) {
        this.ingestionService = ingestionService;
        this.videoCatalog = videoCatalog;
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
     * Everything that can be watched, newest first.
     *
     * <p>Read from disk rather than from job state, because the two disagree: job records are
     * per-process without Redis and expire under a TTL with it, while the media outlives both. A
     * catalogue built on jobs would go empty after a restart with videos still sitting in the HLS
     * root, which is exactly the case this endpoint exists to serve.
     *
     * <p>{@code no-store} for the same reason the not-found playlist carries it: the list changes the
     * moment an ingestion finishes, and a cached copy would keep telling a viewer their video is not
     * there yet.
     */
    @GetMapping
    public ResponseEntity<List<VideoSummaryResponse>> listVideos() {
        List<VideoSummaryResponse> videos =
                videoCatalog.list().stream().map(VideoSummaryResponse::from).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(videos);
    }

    /**
     * Ingestions still running, so a client that lost track of one can pick it back up.
     *
     * <p>Separate from the catalogue above rather than merged into it, which ADR-0011 decided
     * deliberately: an in-progress ingestion has nothing to play, and listing it beside finished
     * videos would put entries in the library that cannot be clicked. What that decision did not
     * anticipate is that the id lived only in the tab that started the ingestion — so a refresh
     * abandoned a perfectly healthy download with no way back to it. This is that way back.
     *
     * <p>Mapped above {@code /{videoId}} and matched ahead of it regardless of order: Spring ranks a
     * literal segment over a template one. Ids are UUIDs, so nothing can collide with "active".
     *
     * <p>{@code no-store} for the same reason as the listing: the answer changes as jobs finish.
     */
    @GetMapping("/active")
    public ResponseEntity<List<StreamJobResponse>> listActiveJobs() {
        List<StreamJobResponse> active =
                ingestionService.listActiveJobs().stream().map(StreamJobResponse::from).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(active);
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
