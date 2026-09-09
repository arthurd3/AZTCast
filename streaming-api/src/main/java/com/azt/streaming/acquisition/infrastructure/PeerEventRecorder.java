package com.azt.streaming.acquisition.infrastructure;

import bt.event.PeerBitfieldUpdatedEvent;
import bt.event.PeerConnectedEvent;
import bt.event.PeerDisconnectedEvent;
import bt.event.PeerDiscoveredEvent;
import bt.metainfo.TorrentId;
import bt.net.ConnectionKey;
import bt.net.Peer;
import bt.runtime.BtRuntime;
import com.azt.streaming.acquisition.domain.PeerObservation;
import com.azt.streaming.acquisition.domain.PeerObservationSink;
import java.time.Clock;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Turns the library's peer events into {@link PeerObservation}s.
 *
 * <p>The runtime's event bus is the only place peers are visible as they come and go. It is also
 * only reachable because the runtime is built and shared rather than hidden inside a per-download
 * client — the same restructuring that made the extension switches configurable.
 *
 * <p>Subscriptions are per torrent and, once made, permanent: the library's {@code EventSource} has
 * no way to unsubscribe. So a torrent is subscribed at most once ever, and the mapping from torrent
 * to ingestion is what gets added and removed. An event for a torrent nobody is tracking is
 * dropped, which is what makes a second ingestion of the same magnet cost no extra listener.
 *
 * <p>This is also where everything the wire hooks accumulated gets attached — the client name, the
 * byte counters — because an event is already a moment when something is being written. Nothing on
 * the message path writes anything itself.
 */
@Slf4j
@Component
public class PeerEventRecorder {

    private final BtRuntime runtime;
    private final PeerObservationSink sink;
    private final PeerFactsRegistry facts;
    private final Clock clock;

    /** The ingestion each torrent currently belongs to. Absent means "not being tracked". */
    private final Map<TorrentId, Tracked> tracked = new ConcurrentHashMap<>();

    /** Torrents already subscribed, because subscribing twice would double every observation. */
    private final Set<TorrentId> subscribed = new HashSet<>();

    /**
     * When each live connection was established, so a disconnect can say how long it lasted.
     *
     * <p>Taken from the events' own timestamps rather than the clock: they are the moments the
     * library actually observed, and the queue between here and the database means "now" at write
     * time can be later than either.
     */
    private final Map<ConnectionKey, Long> connectedAtMillis = new ConcurrentHashMap<>();

    public PeerEventRecorder(
            BtRuntime btRuntime, PeerObservationSink peerObservationSink, PeerFactsRegistry facts, Clock clock) {
        this.runtime = btRuntime;
        this.sink = peerObservationSink;
        this.facts = facts;
        this.clock = clock;
    }

    private record Tracked(String videoId, String infoHash) {}

    /** Starts attributing {@code torrentId}'s peers to {@code videoId}. */
    public synchronized void track(TorrentId torrentId, String videoId) {
        tracked.put(torrentId, new Tracked(videoId, torrentId.toString()));
        if (!subscribed.add(torrentId)) {
            return;
        }
        runtime.getEventSource()
                .onPeerDiscovered(torrentId, event -> onDiscovered(torrentId, event))
                .onPeerConnected(torrentId, this::onConnected)
                .onPeerDisconnected(torrentId, this::onDisconnected)
                .onPeerBitfieldUpdated(torrentId, this::onBitfield);
    }

    /** Stops attributing peers, once the download is over. The subscription itself stays. */
    public void untrack(TorrentId torrentId) {
        tracked.remove(torrentId);
    }

    /**
     * Writes down what every live connection has transferred so far.
     *
     * <p>Called from the download's own poll loop, because the byte counters are only true while
     * the connection is open and none of the peer events happens at a useful moment to read them:
     * a connection has moved nothing when it is established, and when it closes the download has
     * usually finished and stopped being attributed to anything.
     *
     * <p>Cheap by construction — a map lookup per peer and a queue offer, against a swarm of a few
     * dozen. The caller decides how often, and does not do it every poll.
     */
    public void sampleTransfers(TorrentId torrentId, Set<ConnectionKey> connected) {
        long now = clock.millis();
        connected.forEach(connection -> emit(
                torrentId,
                connection.getPeer(),
                connection.getRemotePort(),
                connection,
                null,
                null,
                secondsConnected(connection, now),
                PeerObservation.Kind.TRANSFER));
    }

    private void onDiscovered(TorrentId torrentId, PeerDiscoveredEvent event) {
        // No connection, so no connection key: nothing has been learned about this peer beyond the
        // fact that some source named it.
        emit(torrentId, event.getPeer(), portOf(event.getPeer()), null, null, null, null, PeerObservation.Kind.DISCOVERED);
    }

    private void onConnected(PeerConnectedEvent event) {
        connectedAtMillis.put(event.getConnectionKey(), event.getTimestamp());
        emit(
                event.getTorrentId(),
                event.getPeer(),
                event.getRemotePort(),
                event.getConnectionKey(),
                null,
                null,
                null,
                PeerObservation.Kind.CONNECTED);
    }

    private void onDisconnected(PeerDisconnectedEvent event) {
        ConnectionKey key = event.getConnectionKey();
        emit(
                event.getTorrentId(),
                event.getPeer(),
                event.getRemotePort(),
                key,
                null,
                null,
                secondsConnected(key, event.getTimestamp()),
                PeerObservation.Kind.DISCONNECTED);

        // After the observation, or the final byte counts and the client name would be gone before
        // they were recorded.
        facts.forget(key);
        connectedAtMillis.remove(key);
    }

    private void onBitfield(PeerBitfieldUpdatedEvent event) {
        emit(
                event.getTorrentId(),
                event.getPeer(),
                event.getConnectionKey().getRemotePort(),
                event.getConnectionKey(),
                event.getBitfield().getPiecesComplete(),
                event.getBitfield().getPiecesTotal(),
                null,
                PeerObservation.Kind.BITFIELD);
    }

    private void emit(
            TorrentId torrentId,
            Peer peer,
            int port,
            ConnectionKey connection,
            Integer piecesComplete,
            Integer piecesTotal,
            Integer connectedSeconds,
            PeerObservation.Kind kind) {

        Tracked owner = tracked.get(torrentId);
        if (owner == null) {
            return;
        }
        try {
            sink.record(new PeerObservation(
                    owner.videoId(),
                    owner.infoHash(),
                    peer.getInetAddress().getHostAddress(),
                    port,
                    connection == null ? null : facts.clientOf(connection),
                    piecesComplete,
                    piecesTotal,
                    connection == null ? null : facts.downloadedFrom(connection),
                    connection == null ? null : facts.uploadedTo(connection),
                    connectedSeconds,
                    connection == null ? java.util.Set.of() : facts.capabilitiesOf(connection),
                    kind,
                    clock.instant()));
        } catch (RuntimeException e) {
            // This runs on the library's own threads. A sink that throws must not be allowed to
            // take a connection handler down with it: the provider log is an observation, and
            // losing one is not a reason to disturb the download it was observing.
            log.warn("Failed to record a peer observation for {}", owner.videoId(), e);
        }
    }

    /**
     * How long the connection has been open, or null if its start was never seen.
     *
     * <p>Read on every sample rather than only at the end. Waiting for the disconnect looked
     * correct and recorded nothing: the events that close a torrent's connections arrive around the
     * moment it stops being tracked, so the one observation that knew the duration was the one most
     * likely to be dropped. Sampling a live connection needs no such luck.
     */
    private Integer secondsConnected(ConnectionKey connection, long atMillis) {
        Long connectedAt = connectedAtMillis.get(connection);
        if (connectedAt == null) {
            return null;
        }
        return (int) Math.max(0, (atMillis - connectedAt) / 1000);
    }

    /** A discovered peer may not have announced a port yet. */
    private static int portOf(Peer peer) {
        return peer.isPortUnknown() ? 0 : peer.getPort();
    }
}
