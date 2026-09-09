package com.azt.streaming.acquisition.domain;

import java.util.Optional;

/**
 * How the swarm sees us.
 *
 * <p>A port, so that a page can report this without reaching into the acquisition slice's
 * internals — the same reason {@link PeerObservationSink} is one, pointing the other way.
 *
 * <p>Worth having as its own concept because it is the only direct evidence available that outbound
 * traffic is masked. Everything else on the subject is configuration claiming what ought to happen;
 * this is a remote peer reporting what it actually observed.
 */
public interface SwarmSelfView {

    /**
     * The address peers report seeing us as, or empty until one says.
     *
     * <p>Self-reported by remote peers, and only sent by clients that implement the extended
     * handshake — so an empty answer means nobody has told us, never that nothing is masked.
     */
    Optional<String> addressPeersSee();
}
