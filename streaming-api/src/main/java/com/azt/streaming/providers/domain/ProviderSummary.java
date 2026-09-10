package com.azt.streaming.providers.domain;

import java.time.Instant;
import java.util.List;

/**
 * The provenance of everything downloaded, aggregated for one page.
 *
 * <p>Assembled in SQL rather than by reading every peer row into memory and grouping there. A
 * download's swarm is hundreds of rows and the log holds a month of them; counting and summing is
 * exactly what the database is for, and it keeps the page to one request instead of one per video.
 *
 * @param places one entry per distinct location, not per peer. Many addresses resolve to the same
 *     centroid — a city, or a country's notional middle when nothing better is known — so grouping
 *     is not an optimisation here, it is the only honest way to draw them. Forty peers at one
 *     coordinate are one place that served forty times, not forty places.
 */
public record ProviderSummary(
        Totals totals,
        List<VideoProvenance> videos,
        List<Place> places,
        Distributions distributions) {

    /**
     * @param hostingPeers peers whose network looks like a datacenter or a VPN exit. A share rather
     *     than a verdict — see {@code NetworkKind}.
     * @param recurringPeers addresses that turned up in more than one download.
     */
    public record Totals(
            int videos,
            int peers,
            int connected,
            int countries,
            int networks,
            long bytesDownloaded,
            int hostingPeers,
            int recurringPeers) {}

    /** Who is serving, counted three ways. */
    public record Distributions(List<Slice> clients, List<Slice> countries, List<Slice> networks) {}

    /** One bar: a label, how many peers wore it, and how much they sent. */
    public record Slice(String label, int peers, long bytesDownloaded) {}

    /**
     * One downloaded video and the swarm behind it.
     *
     * @param available whether the media is still on disk. Peer rows outlive it deliberately —
     *     30 days against the media's 7 — so a false here is the normal end state of an old
     *     ingestion, not a fault. The record of where something came from is worth keeping after
     *     the something is gone.
     */
    public record VideoProvenance(
            String videoId,
            String title,
            Instant readyAt,
            boolean available,
            boolean hasPoster,
            int peerCount,
            int connectedCount,
            int seederCount,
            long bytesDownloaded,
            Instant firstSeen,
            Instant lastSeen) {}

    /** One point on the map, which is to say one place several peers resolved to. */
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
}
