package com.azt.streaming.providers.domain;

import java.time.Instant;
import java.util.List;

/** Stores what was learned about the peers behind each video. */
public interface ProviderPeerRepository {

    /**
     * Records a sighting, merging it into whatever is already known about that peer.
     *
     * <p>An upsert rather than an insert-per-event: the interesting record is one row per peer per
     * video, carrying when it was first and last seen, not a log line for every packet. A peer in a
     * long download is seen thousands of times.
     */
    void record(ProviderPeer peer);

    /** Every peer known for one video, most recently seen first. */
    List<ProviderPeer> findByVideoId(String videoId);

    /** Every peer known, most recently seen first, capped. */
    List<ProviderPeer> findRecent(int limit);

    /** Drops rows last seen before {@code cutoff}. Returns how many went. */
    int deleteOlderThan(Instant cutoff);

    /** Counts across the whole log. */
    ProviderSummary.Totals totals();

    /** One row per video that has peers, whether or not its media still exists. */
    List<VideoAggregate> aggregateByVideo();

    /**
     * One row per distinct location, for the map.
     *
     * @param videoId narrows to a single ingestion, or null for everything
     */
    List<ProviderSummary.Place> aggregateByPlace(String videoId);

    /**
     * The top {@code limit} values of one column, by peer count.
     *
     * <p>Counted in SQL rather than by reading every row into the browser and grouping there: the
     * whole point of the aggregate endpoint is that the database does the counting it is good at.
     *
     * @param column one of a fixed set — never caller-supplied text, since it is interpolated
     */
    List<ProviderSummary.Slice> topValuesOf(Dimension dimension, int limit);

    /** Columns a distribution may be taken over. A closed set, so no SQL is ever built from input. */
    enum Dimension {
        CLIENT("client"),
        COUNTRY("country"),
        NETWORK("network");

        private final String column;

        Dimension(String column) {
            this.column = column;
        }

        public String column() {
            return column;
        }
    }

    /**
     * A video's swarm, counted.
     *
     * <p>Separate from {@link ProviderSummary.VideoProvenance} because this half comes from the peer
     * log and the other half — title, poster, whether the media survives — comes from the
     * catalogue on disk. Joining them is the service's job, not the repository's.
     */
    record VideoAggregate(
            String videoId,
            int peerCount,
            int connectedCount,
            int seederCount,
            long bytesDownloaded,
            Instant firstSeen,
            Instant lastSeen) {}
}
