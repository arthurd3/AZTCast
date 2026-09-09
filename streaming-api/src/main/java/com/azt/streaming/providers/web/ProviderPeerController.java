package com.azt.streaming.providers.web;

import com.azt.streaming.acquisition.domain.SwarmSelfView;
import com.azt.streaming.providers.application.ProviderPeerLog;
import com.azt.streaming.providers.web.dto.ProviderPeerResponse;
import com.azt.streaming.providers.web.dto.ProviderSummaryResponse;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who served what.
 *
 * <p>Only mapped when the provider log is enabled, so the endpoints are absent rather than empty
 * when it is off — a 404 says "not recording" far more clearly than an empty array does.
 *
 * <p>Read-only, and never cached: peers come and go for the whole of a download.
 */
@RestController
@ConditionalOnProperty(prefix = "aztcast.streaming.providers", name = "enabled", havingValue = "true")
public class ProviderPeerController {

    /** A swarm is hundreds of peers; the whole log across every video is not. */
    private static final int DEFAULT_LIMIT = 200;

    private static final int MAX_LIMIT = 2_000;

    private final ProviderPeerLog providerPeerLog;
    private final SwarmSelfView swarmSelfView;

    public ProviderPeerController(ProviderPeerLog providerPeerLog, SwarmSelfView swarmSelfView) {
        this.providerPeerLog = providerPeerLog;
        this.swarmSelfView = swarmSelfView;
    }

    /**
     * Everything the provenance page draws: totals, one entry per video, and one entry per place.
     *
     * <p>{@code videoId} narrows the places to a single ingestion, which is what selecting a video
     * on the page does. The totals and the video list stay whole either way — they are the context
     * the selection is made against.
     */
    @GetMapping("/api/v1/providers/summary")
    public ResponseEntity<ProviderSummaryResponse> summary(
            @RequestParam(required = false) String videoId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ProviderSummaryResponse.from(
                        providerPeerLog.summary(videoId), swarmSelfView.addressPeersSee().orElse(null)));
    }

    /** Every peer seen for one video. Empty for a video that was never ingested by this instance. */
    @GetMapping("/api/v1/videos/{videoId}/peers")
    public ResponseEntity<List<ProviderPeerResponse>> peersOf(@PathVariable String videoId) {
        return noStore(providerPeerLog.forVideo(videoId).stream()
                .map(ProviderPeerResponse::from)
                .toList());
    }

    /** The whole log, most recently seen first. */
    @GetMapping("/api/v1/peers")
    public ResponseEntity<List<ProviderPeerResponse>> peers(
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        int capped = Math.clamp(limit, 1, MAX_LIMIT);
        return noStore(providerPeerLog.recent(capped).stream()
                .map(ProviderPeerResponse::from)
                .toList());
    }

    private static ResponseEntity<List<ProviderPeerResponse>> noStore(List<ProviderPeerResponse> body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
