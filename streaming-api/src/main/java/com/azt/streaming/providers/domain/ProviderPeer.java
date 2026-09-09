package com.azt.streaming.providers.domain;

import java.time.Instant;
import java.util.List;

/**
 * One peer that served, or offered to serve, one video.
 *
 * <p>This is the whole of what BitTorrent discloses about a provider, and it is worth stating the
 * ceiling plainly: an address, a port, whatever software the peer volunteers about itself, and how
 * much of the torrent it claims to hold. There is no name, no account and no contact detail,
 * because the protocol has no such concept. The location fields are inferred from the address
 * against a local database — see {@link PeerLocation}.
 *
 * <p>An address is personal data under the LGPD even though none of the above identifies a person,
 * which is why these rows expire on a retention window like everything else here.
 *
 * @param timesConnected how often a connection to this peer was established, which separates a peer
 *     that served steadily from one that appeared once in a tracker response.
 * @param bytesDownloaded how much this peer actually sent us. The strongest thing in this record:
 *     everything else says a peer was present, this says what it gave.
 * @param bytesUploaded how much was sent back to it.
 * @param connectedSeconds the longest observed connection to this peer, in seconds.
 * @param capabilities protocol extensions the peer announced. Empty when it announced none, or when
 *     the sighting never got as far as a handshake.
 * @param networkKind whether the address looks like a datacenter or a home. Derived on read from
 *     {@code location}, not stored — a guess should be free to improve without rewriting history.
 * @param videosServed how many of this instance's downloads this address has appeared in.
 */
public record ProviderPeer(
        String videoId,
        String infoHash,
        String ipAddress,
        int port,
        String client,
        PeerLocation location,
        PeerRole role,
        Integer piecesComplete,
        Integer piecesTotal,
        int timesConnected,
        long bytesDownloaded,
        long bytesUploaded,
        int connectedSeconds,
        List<String> capabilities,
        NetworkKind networkKind,
        int videosServed,
        Instant firstSeen,
        Instant lastSeen) {}
