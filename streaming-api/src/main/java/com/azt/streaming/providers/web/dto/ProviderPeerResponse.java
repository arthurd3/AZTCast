package com.azt.streaming.providers.web.dto;

import com.azt.streaming.providers.domain.ProviderPeer;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * One provider, as the API reports it.
 *
 * <p>Flattened out of {@link ProviderPeer}'s nested location, because a client drawing a table
 * wants columns. {@code NON_NULL} because most of these are unknown for most peers: a peer that
 * never handshook has no client, and without a {@code .mmdb} none of them have a location.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderPeerResponse(
        String videoId,
        String ipAddress,
        int port,
        String client,
        String countryCode,
        String country,
        String city,
        Long asn,
        String network,
        String networkKind,
        Double latitude,
        Double longitude,
        Integer accuracyRadiusKm,
        String role,
        Integer completePercent,
        int timesConnected,
        long bytesDownloaded,
        long bytesUploaded,
        int connectedSeconds,
        List<String> capabilities,
        int videosServed,
        Instant firstSeen,
        Instant lastSeen) {

    public static ProviderPeerResponse from(ProviderPeer peer) {
        return new ProviderPeerResponse(
                peer.videoId(),
                peer.ipAddress(),
                peer.port(),
                peer.client(),
                peer.location().countryCode(),
                peer.location().country(),
                peer.location().city(),
                peer.location().asn(),
                peer.location().network(),
                peer.networkKind() == null ? null : peer.networkKind().name(),
                peer.location().latitude(),
                peer.location().longitude(),
                peer.location().accuracyRadiusKm(),
                peer.role().name(),
                completePercent(peer),
                peer.timesConnected(),
                peer.bytesDownloaded(),
                peer.bytesUploaded(),
                peer.connectedSeconds(),
                peer.capabilities(),
                peer.videosServed(),
                peer.firstSeen(),
                peer.lastSeen());
    }

    /** How much of the torrent the peer claimed to hold, or null if it never said. */
    private static Integer completePercent(ProviderPeer peer) {
        if (peer.piecesComplete() == null || peer.piecesTotal() == null || peer.piecesTotal() <= 0) {
            return null;
        }
        return (int) Math.round(100.0 * peer.piecesComplete() / peer.piecesTotal());
    }
}
