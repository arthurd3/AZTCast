package com.azt.streaming.acquisition.infrastructure;

import bt.net.ConnectionKey;
import com.azt.streaming.acquisition.domain.SwarmSelfView;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

/**
 * What has been learned about each live connection, held in memory until it is written out.
 *
 * <p>Everything interesting about a peer arrives on a different message at a different moment: the
 * client name on a handshake, a better client name on an extended handshake that may never come,
 * byte counters that only move while blocks are arriving. None of those moments is a good time to
 * write to a database — they are on the library's own threads, and blocks arrive thousands of times
 * a second. So they accumulate here, and the peer-event path picks them up when it is already
 * writing an observation for its own reasons. The provider log's write rate is unchanged by any of
 * this.
 *
 * <p>Bounded and cleared wholesale past the cap, for the reason the client-version cache was: the
 * contents decorate a peer row, so losing them costs some blank columns, while leaking them costs
 * the heap. Entries are removed on disconnect, but a disconnect can be missed.
 */
@Slf4j
public class PeerFactsRegistry implements SwarmSelfView {

    private static final int MAX_ENTRIES = 10_000;

    private final Map<ConnectionKey, Facts> byConnection = new ConcurrentHashMap<>();

    /**
     * The address peers say they see us as, from the extended handshake's {@code yourip}.
     *
     * <p>Global rather than per peer, because it describes <em>us</em>. It is the swarm's own answer
     * to whether a tunnel is doing anything: if this is the VPN's address, masking is working, and
     * if it is the machine's own, it is not. Last writer wins — peers should agree, and when they do
     * not it is usually one reporting an IPv6 address and another IPv4.
     */
    private volatile String addressPeersSee;

    /** Mutable on purpose: this is a hot-path accumulator, not a value. */
    private static final class Facts {
        private volatile String peerIdClient;
        private volatile String versionClient;
        private final AtomicLong downloaded = new AtomicLong();
        private final AtomicLong uploaded = new AtomicLong();
        /** Ordered so the rendered badges do not shuffle between sightings of the same peer. */
        private final Set<String> capabilities = Collections.synchronizedSet(new LinkedHashSet<>());
    }

    private Facts factsFor(ConnectionKey connection) {
        if (byConnection.size() >= MAX_ENTRIES) {
            log.debug("Peer facts cache full; clearing {} entries", byConnection.size());
            byConnection.clear();
        }
        return byConnection.computeIfAbsent(connection, key -> new Facts());
    }

    /** From the base handshake's peer_id — present for very nearly every peer. */
    public void recordPeerIdClient(ConnectionKey connection, String client) {
        if (client != null) {
            factsFor(connection).peerIdClient = client;
        }
    }

    /** From the extended handshake's {@code v} — rarer, and better when it is there. */
    public void recordVersionClient(ConnectionKey connection, String client) {
        if (client != null) {
            factsFor(connection).versionClient = client;
        }
    }

    /**
     * Notes something the peer announced it can do.
     *
     * <p>Accumulated rather than replaced: the flags arrive on two different messages — the base
     * handshake's reserved bits and the extended handshake's extension map — and the second must not
     * erase what the first established.
     */
    public void recordCapability(ConnectionKey connection, String capability) {
        factsFor(connection).capabilities.add(capability);
    }

    /** What this peer said it supports, in the order it said it. Never null. */
    public Set<String> capabilitiesOf(ConnectionKey connection) {
        Facts facts = byConnection.get(connection);
        if (facts == null) {
            return Set.of();
        }
        synchronized (facts.capabilities) {
            return Set.copyOf(facts.capabilities);
        }
    }

    public void recordAddressPeersSee(String address) {
        if (address != null) {
            this.addressPeersSee = address;
        }
    }

    /** Cumulative byte counters for this connection, as the library last reported them. */
    public void recordTransfer(ConnectionKey connection, long downloaded, long uploaded) {
        Facts facts = factsFor(connection);
        facts.downloaded.set(downloaded);
        facts.uploaded.set(uploaded);
    }

    /**
     * The best name available for this connection's software, or null.
     *
     * <p>The extended handshake wins when present: it is a string the client chose to describe
     * itself, where the peer_id is a two-letter code plus four digits that has to be looked up.
     */
    public String clientOf(ConnectionKey connection) {
        Facts facts = byConnection.get(connection);
        if (facts == null) {
            return null;
        }
        return facts.versionClient != null ? facts.versionClient : facts.peerIdClient;
    }

    /** Bytes this peer has sent us on this connection, or 0 if it never sent any. */
    public long downloadedFrom(ConnectionKey connection) {
        Facts facts = byConnection.get(connection);
        return facts == null ? 0L : facts.downloaded.get();
    }

    /** Bytes sent to this peer on this connection. */
    public long uploadedTo(ConnectionKey connection) {
        Facts facts = byConnection.get(connection);
        return facts == null ? 0L : facts.uploaded.get();
    }

    @Override
    public Optional<String> addressPeersSee() {
        return Optional.ofNullable(addressPeersSee);
    }

    /** Called once a disconnect is observed, after the final observation has been written. */
    public void forget(ConnectionKey connection) {
        byConnection.remove(connection);
    }
}
