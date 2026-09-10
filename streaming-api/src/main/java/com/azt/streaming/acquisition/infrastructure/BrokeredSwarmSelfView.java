package com.azt.streaming.acquisition.infrastructure;

import com.azt.streaming.acquisition.domain.SwarmSelfView;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link SwarmSelfView} fed by the out-of-process engine.
 *
 * <p>The embedded path reads this out of extended handshakes it is party to. The brokered path is
 * not party to anything — the handshakes happen in another process — so the engine reports the
 * address it deduced and this holds the last one it sent.
 *
 * <p>Empty until a peer says, exactly as before. That distinction is the whole value of the field:
 * an empty answer means nobody has told us, and never that nothing is masked.
 */
public class BrokeredSwarmSelfView implements SwarmSelfView {

    private final AtomicReference<String> address = new AtomicReference<>();

    @Override
    public Optional<String> addressPeersSee() {
        return Optional.ofNullable(address.get());
    }

    /** Called from a download's reader thread whenever the engine reports the address. */
    void report(String reported) {
        if (reported != null && !reported.isBlank()) {
            address.set(reported);
        }
    }
}
