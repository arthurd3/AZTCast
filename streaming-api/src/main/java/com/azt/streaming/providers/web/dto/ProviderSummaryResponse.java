package com.azt.streaming.providers.web.dto;

import com.azt.streaming.providers.domain.ProviderSummary;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Everything the provenance page needs, in one response.
 *
 * <p>One request rather than one per video: the page is an aggregate view, and asking the browser to
 * fan out and re-join what SQLite already grouped would be slower and would say the same thing.
 *
 * @param addressPeersSee the address the swarm reports seeing us as, or absent if no peer has said.
 *     Included here because it belongs to the same question the page answers — who saw what — and
 *     because it is the only direct evidence available of whether outbound traffic is masked.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderSummaryResponse(
        Totals totals,
        List<Video> videos,
        List<Place> places,
        Distributions distributions,
        String addressPeersSee) {

    public record Totals(
            int videos,
            int peers,
            int connected,
            int countries,
            int networks,
            long bytesDownloaded,
            int hostingPeers,
            int recurringPeers) {}

    public record Distributions(List<Slice> clients, List<Slice> countries, List<Slice> networks) {}

    public record Slice(String label, int peers, long bytesDownloaded) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Video(
            String videoId,
            String title,
            String posterUrl,
            Instant readyAt,
            boolean available,
            int peerCount,
            int connectedCount,
            int seederCount,
            long bytesDownloaded,
            Instant firstSeen,
            Instant lastSeen) {}

    /**
     * One place on the map.
     *
     * @param accuracyRadiusKm the radius the estimate is good to. Sent so the page can draw it —
     *     a coordinate without it invites a reader to believe a precision that is not there.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Place(
            double latitude,
            double longitude,
            Integer accuracyRadiusKm,
            String city,
            String country,
            String countryCode,
            int peerCount,
            int connectedCount,
            long bytesDownloaded,
            List<String> networks) {}

    public static ProviderSummaryResponse from(ProviderSummary summary, String addressPeersSee) {
        return new ProviderSummaryResponse(
                new Totals(
                        summary.totals().videos(),
                        summary.totals().peers(),
                        summary.totals().connected(),
                        summary.totals().countries(),
                        summary.totals().networks(),
                        summary.totals().bytesDownloaded(),
                        summary.totals().hostingPeers(),
                        summary.totals().recurringPeers()),
                summary.videos().stream().map(ProviderSummaryResponse::video).toList(),
                summary.places().stream().map(ProviderSummaryResponse::place).toList(),
                new Distributions(
                        slices(summary.distributions().clients()),
                        slices(summary.distributions().countries()),
                        slices(summary.distributions().networks())),
                addressPeersSee);
    }

    private static Video video(ProviderSummary.VideoProvenance provenance) {
        return new Video(
                provenance.videoId(),
                provenance.title(),
                // Built as a literal rather than borrowed from the ingestion slice: a URL shape is
                // not worth a dependency between features. There is deliberately no streamUrl —
                // `available` is all a client needs, and it already knows how to build a watch link.
                provenance.hasPoster() ? "/api/v1/stream/" + provenance.videoId() + "/poster.jpg" : null,
                provenance.readyAt(),
                provenance.available(),
                provenance.peerCount(),
                provenance.connectedCount(),
                provenance.seederCount(),
                provenance.bytesDownloaded(),
                provenance.firstSeen(),
                provenance.lastSeen());
    }

    private static List<Slice> slices(List<ProviderSummary.Slice> source) {
        return source.stream()
                .map(slice -> new Slice(slice.label(), slice.peers(), slice.bytesDownloaded()))
                .toList();
    }

    private static Place place(ProviderSummary.Place source) {
        return new Place(
                source.latitude(),
                source.longitude(),
                source.accuracyRadiusKm(),
                source.city(),
                source.country(),
                source.countryCode(),
                source.peerCount(),
                source.connectedCount(),
                source.bytesDownloaded(),
                source.networks());
    }
}
