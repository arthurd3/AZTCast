package com.azt.streaming.providers.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PeerRoleTest {

    @Test
    void aPeerHoldingEveryPieceIsASeeder() {
        assertThat(PeerRole.of(100, 100)).isEqualTo(PeerRole.SEEDER);
    }

    @Test
    void aPeerStillMissingPiecesIsALeecher() {
        assertThat(PeerRole.of(99, 100)).isEqualTo(PeerRole.LEECHER);
    }

    @Test
    void nothingIsAssumedBeforeTheBitfieldArrives() {
        // The common case: most sightings are discoveries and connections, which say nothing about
        // what the peer holds. Guessing "leecher" there would misdescribe most of a swarm.
        assertThat(PeerRole.of(null, null)).isEqualTo(PeerRole.UNKNOWN);
        assertThat(PeerRole.of(5, null)).isEqualTo(PeerRole.UNKNOWN);
        assertThat(PeerRole.of(0, 0)).isEqualTo(PeerRole.UNKNOWN);
    }
}
