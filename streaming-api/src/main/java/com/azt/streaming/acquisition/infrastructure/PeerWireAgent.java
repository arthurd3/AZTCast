package com.azt.streaming.acquisition.infrastructure;

import bt.bencoding.model.BEObject;
import bt.bencoding.types.BEString;
import bt.protocol.Piece;
import bt.protocol.extended.ExtendedHandshake;
import bt.torrent.annotation.Consumes;
import bt.torrent.messaging.MessageContext;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Watches the peer wire for the things the library records nowhere.
 *
 * <p>A messaging agent is the supported way to see raw protocol messages, and two of them carry
 * information that is otherwise lost the moment it arrives.
 *
 * <p>Everything read here is <em>self-reported by the remote peer</em> and can be anything it likes.
 * It describes the shape of a swarm. It identifies nobody.
 */
@Slf4j
@RequiredArgsConstructor
public class PeerWireAgent {

    /** Sent by the remote peer as its external view of <em>our</em> address. Not written by bt. */
    private static final String YOUR_IP_PROPERTY = "yourip";

    private final PeerFactsRegistry facts;

    /**
     * The extended handshake: a better client name than the peer_id gives, and {@code yourip}.
     *
     * <p>Optional in the protocol, so most of what is learned here is a bonus on top of what the
     * base handshake already established — see {@link PeerHandshakeInspector}.
     */
    @Consumes
    public void consume(ExtendedHandshake handshake, MessageContext context) {
        BEObject<?> version = handshake.getData().get(ExtendedHandshake.VERSION_PROPERTY);
        if (version instanceof BEString name) {
            facts.recordVersionClient(context.getConnectionKey(), name.getValueAsString());
        }
        readAddress(handshake.getData().get(YOUR_IP_PROPERTY)).ifPresent(facts::recordAddressPeersSee);

        // The "m" map: which named extensions this client implements. Two are worth recording —
        // peer exchange, because a peer that does it is a route to more peers, and metadata
        // exchange, because it is how a magnet becomes a torrent at all.
        var supported = handshake.getSupportedMessageTypes();
        if (supported.contains("ut_pex")) {
            facts.recordCapability(context.getConnectionKey(), "PEX");
        }
        if (supported.contains("ut_metadata")) {
            facts.recordCapability(context.getConnectionKey(), "METADATA");
        }
    }

    /**
     * Every block of data that arrives, purely to sample the connection's byte counters.
     *
     * <p>The counters live on {@code ConnectionState}, which is reachable only through a message
     * context — there is no way to ask for them out of band. They are cumulative for the life of the
     * connection, so the latest sample is the answer and nothing needs accumulating here.
     *
     * <p>The message itself is ignored. This fires once per block, which is thousands of times a
     * second on a fast torrent, so it must stay two field reads and a map write: the registry it
     * writes to exists precisely so that nothing on this path touches a database.
     */
    @Consumes
    public void consume(Piece block, MessageContext context) {
        var state = context.getConnectionState();
        facts.recordTransfer(context.getConnectionKey(), state.getDownloaded(), state.getUploaded());
    }

    /** {@code yourip} is 4 or 16 raw bytes, not a string. */
    private static Optional<String> readAddress(BEObject<?> value) {
        if (!(value instanceof BEString raw)) {
            return Optional.empty();
        }
        byte[] bytes = raw.getValue();
        if (bytes.length != 4 && bytes.length != 16) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByAddress(bytes).getHostAddress());
        } catch (UnknownHostException e) {
            // Only thrown for a length that is not 4 or 16, which is already excluded.
            return Optional.empty();
        }
    }
}
