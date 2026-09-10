package com.azt.streaming.acquisition.infrastructure;

import bt.net.ConnectionKey;
import bt.net.HandshakeHandler;
import bt.net.PeerConnection;
import bt.protocol.Handshake;
import lombok.RequiredArgsConstructor;

/**
 * Reads each peer's identity off the base handshake.
 *
 * <p>A {@code HandshakeHandler} rather than a messaging agent, because there is no choice: the
 * handshake is consumed by the connection handler before the message router exists, so a
 * {@code @Consumes} method never sees one. This is the only hook that does.
 *
 * <p>It is worth the extra wiring. The peer_id here is sent by essentially every client before
 * anything else, where the extended handshake this used to rely on is optional and frequently
 * absent — against a real swarm the difference was 9 identified peers out of 71 versus nearly all
 * of them.
 *
 * <p>Read-only: it inspects and records, and changes nothing about the handshake or the connection.
 */
@RequiredArgsConstructor
public class PeerHandshakeInspector implements HandshakeHandler {

    /**
     * Reserved-bit positions, in the library's own little-endian numbering.
     *
     * <p>These are the conventional flags from BEP-5, BEP-10 and BEP-6 — {@code reserved[7] & 0x01},
     * {@code reserved[5] & 0x10} and {@code reserved[7] & 0x04} respectively, which land on these
     * indices once the bytes are read as one 64-bit field.
     */
    private static final int DHT_BIT = 63;

    private static final int EXTENSION_PROTOCOL_BIT = 43;
    private static final int FAST_EXTENSION_BIT = 61;

    private final PeerFactsRegistry facts;

    @Override
    public void processIncomingHandshake(PeerConnection connection, Handshake peerHandshake) {
        // Fires for every connection, inbound and outbound alike — "incoming" describes the
        // handshake's direction, not the connection's.
        ConnectionKey connectionKey = new ConnectionKey(
                connection.getRemotePeer(), connection.getRemotePort(), peerHandshake.getTorrentId());

        String client = PeerIdDecoder.decode(peerHandshake.getPeerId().getBytes());
        if (client != null) {
            facts.recordPeerIdClient(connectionKey, client);
        }

        // Eight bytes every peer sends and almost nobody reads. They say what the client can do
        // before a single message is exchanged, which is the only place some of this is knowable.
        record(connectionKey, peerHandshake, DHT_BIT, "DHT");
        record(connectionKey, peerHandshake, EXTENSION_PROTOCOL_BIT, "EXT");
        record(connectionKey, peerHandshake, FAST_EXTENSION_BIT, "FAST");
    }

    private void record(ConnectionKey connection, Handshake handshake, int bit, String capability) {
        if (handshake.isReservedBitSet(bit)) {
            facts.recordCapability(connection, capability);
        }
    }

    @Override
    public void processOutgoingHandshake(Handshake handshake) {
        // Ours, on the way out. Nothing to learn from it.
    }
}
