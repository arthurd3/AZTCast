package com.azt.streaming.ingestion.web;

import com.azt.streaming.ingestion.application.IngestionService;
import com.azt.streaming.ingestion.domain.RepairAction;
import com.azt.streaming.ingestion.domain.StreamJob;
import com.azt.streaming.ingestion.web.dto.CreateStreamJobRequest;
import com.azt.streaming.ingestion.web.dto.RepairResponse;
import com.azt.streaming.ingestion.web.dto.StreamJobResponse;
import com.azt.streaming.ingestion.web.dto.VideoSummaryResponse;
import com.azt.streaming.shared.storage.VideoCatalog;
import com.azt.streaming.shared.storage.VideoNotFoundException;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
     * Keeps a video past the retention window, or stops keeping it.
     *
     * <p>A sub-resource with PUT and DELETE rather than a POST verb, because the thing being set is
     * a flag that is either there or not: PUT twice is the same as PUT once, and DELETE on a video
     * that was never kept is a success, not a 404. Only a video that has no media left is.
     *
     * <p>Not rate limited. {@code IngestionRateLimitConfiguration} registers the exact path
     * {@code /api/v1/videos}, which does not match this one — and should not. The limit exists
     * because starting an ingestion costs hours of CPU and gigabytes of disk; writing a marker file
     * costs neither, and throttling the save button would be throttling nothing worth throttling.
     */
    @PutMapping("/{videoId}/keep")
    public ResponseEntity<Void> keepVideo(@PathVariable String videoId) {
        return setKept(videoId, true);
    }

    @DeleteMapping("/{videoId}/keep")
    public ResponseEntity<Void> stopKeepingVideo(@PathVariable String videoId) {
        return setKept(videoId, false);
    }

    private ResponseEntity<Void> setKept(String videoId, boolean kept) {
        if (!videoCatalog.setKept(videoId, kept)) {
            throw new VideoNotFoundException(videoId);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Puts a video that stopped working back together, under the same id.
     *
     * <p>POST rather than PUT: this is not setting a flag, it is asking for work whose cost depends
     * on what is wrong — anything from rewriting a playlist to fetching the torrent again. It is
     * also not idempotent in the way PUT promises, because the answer changes as the repair runs.
     *
     * <p>Rate limited, unlike {@code /keep} and for the opposite reason: at its most expensive this
     * spends a full download and a full encode, which is exactly what the limit exists to bound.
     *
     * <p>{@code 200} when there was nothing to do, {@code 202} when work has started and the client
     * should poll {@code GET /api/v1/videos/&#123;videoId&#125;} to follow it.
     */
    @PostMapping("/{videoId}/repair")
    public ResponseEntity<RepairResponse> repairVideo(@PathVariable String videoId) {
        RepairAction action = ingestionService.repair(videoId);
        RepairResponse body = new RepairResponse(videoId, action, describe(action));
        return action == RepairAction.NOTHING_TO_DO
                ? ResponseEntity.ok(body)
                : ResponseEntity.accepted()
                        .location(URI.create("/api/v1/videos/" + videoId))
                        .body(body);
    }

    private static String describe(RepairAction action) {
        return switch (action) {
            case NOTHING_TO_DO -> "The ladder is complete and playable; nothing was changed.";
            case AUDIO_REBUILT -> "The ladder is intact, but this host can now produce better audio than it"
                    + " carries. Re-encoding the audio rendition only, which takes seconds and leaves"
                    + " every video rung alone.";
            case MANIFESTS_REBUILT -> "The media was intact and the playlists were not."
                    + " Rebuilding the manifests, which takes seconds and re-encodes nothing.";
            case RETRANSCODED -> "Segments were missing. Transcoding the retained download again.";
            case REFETCHED -> "The download was gone too. Fetching the torrent again from the recorded magnet.";
        };
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
