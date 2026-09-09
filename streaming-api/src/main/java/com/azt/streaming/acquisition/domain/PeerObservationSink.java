package com.azt.streaming.acquisition.domain;

/**
 * Where peer sightings go.
 *
 * <p>A port for the same reason {@link TorrentDownloader} is one: it lets the swarm-facing code
 * report what it sees without knowing whether anything stores it, and it lets the storing side be
 * tested without a swarm. The default binding does nothing, so acquisition works unchanged when the
 * provider log is switched off.
 */
public interface PeerObservationSink {

    /**
     * Records one sighting.
     *
     * <p>Called from the BitTorrent library's own threads, once per peer per event, for every
     * torrent in flight — so implementations must return promptly and must not throw. Anything slow
     * (a database write, a geolocation lookup) belongs behind a queue on the other side of this
     * call, not in front of the swarm.
     */
    void record(PeerObservation observation);

    /** Discards everything. The binding when the provider log is disabled. */
    PeerObservationSink NONE = observation -> {};
}
