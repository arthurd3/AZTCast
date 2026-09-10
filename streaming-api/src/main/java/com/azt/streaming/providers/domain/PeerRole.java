package com.azt.streaming.providers.domain;

/** What a peer was doing for us, as far as it told us. */
public enum PeerRole {
    /** Has the whole torrent. */
    SEEDER,
    /** Still downloading it themselves. */
    LEECHER,
    /** Never sent a bitfield, so there is nothing to go on. */
    UNKNOWN;

    /** Derives the role from what a peer claims to hold. */
    public static PeerRole of(Integer piecesComplete, Integer piecesTotal) {
        if (piecesComplete == null || piecesTotal == null || piecesTotal <= 0) {
            return UNKNOWN;
        }
        return piecesComplete >= piecesTotal ? SEEDER : LEECHER;
    }
}
