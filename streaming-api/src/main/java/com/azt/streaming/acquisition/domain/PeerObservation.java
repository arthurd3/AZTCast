package com.azt.streaming.acquisition.domain;

import java.time.Instant;
import java.util.Set;

/**
 * One sighting of a peer serving a torrent.
 *
 * <p>Deliberately made of plain types. The swarm is the acquisition slice's business, and whoever
 * records these should not have to link against a BitTorrent library to read an IP address — a
 * fitness function keeps {@code bt..} on this side of the boundary.
 *
 * <p>What is here is what BitTorrent actually exposes, which is less than people expect. A peer is
 * an address and a port. There is no account, no name, and no identity beyond that; {@code client}
 * is a string the remote software volunteers about itself and is frequently absent. Anything more —
 * a country, a network operator — is inferred afterwards from the address, not reported by the peer.
 *
 * @param client the remote BitTorrent software, e.g. {@code qBittorrent 4.6.5}, or null when it did
 *     not say. Self-reported and trivially spoofable: useful for seeing the shape of a swarm, not
 *     for identifying anyone.
 * @param piecesComplete how much of the torrent this peer already has, or null before it has told
 *     us. With {@code piecesTotal} this is what separates a seeder from a leecher.
 * @param bytesDownloaded bytes this peer has actually sent us on this connection, or null if the
 *     library has not reported any yet. The truest measure of a provider there is: that a peer
 *     existed is cheap, that it served 340 MB is the thing worth recording.
 * @param bytesUploaded bytes sent to this peer on the same connection.
 * @param connectedSeconds how long the connection lasted, known only when it ends.
 * @param capabilities what the peer announced it supports — DHT, the extension protocol, the fast
 *     extension, peer exchange, metadata exchange. Declared by the peer before any data moves, and
 *     as self-reported as everything else here.
 */
public record PeerObservation(
        String videoId,
        String infoHash,
        String ipAddress,
        int port,
        String client,
        Integer piecesComplete,
        Integer piecesTotal,
        Long bytesDownloaded,
        Long bytesUploaded,
        Integer connectedSeconds,
        Set<String> capabilities,
        Kind kind,
        Instant observedAt) {

    /** Why this sighting happened. */
    public enum Kind {
        /** A tracker, DHT, PEX or LSD source named this peer. No connection was made. */
        DISCOVERED,
        /** A connection to this peer was established. */
        CONNECTED,
        /** The connection ended. */
        DISCONNECTED,
        /** The peer told us how much of the torrent it has. */
        BITFIELD,
        /**
         * A periodic sample of a live connection's byte counters.
         *
         * <p>Needed because the other four are all edges, and the interesting number is not known
         * at any of them: a connection has transferred nothing when it opens, and by the time it
         * closes the download is usually over and no longer being attributed. Bytes are only
         * observable while data is still moving, so something has to look while it is.
         */
        TRANSFER
    }
}
