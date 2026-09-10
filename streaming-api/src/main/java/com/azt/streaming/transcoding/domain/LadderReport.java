package com.azt.streaming.transcoding.domain;

import java.util.List;

/**
 * What is wrong with a ladder, and whether the expensive part of it survived.
 *
 * <p>The two answers cost very different amounts to act on. Manifests can be rewritten in seconds
 * from media that is still correct; media that has lost segments has to be produced again. Returning
 * only a list of complaints would leave the caller matching on message text to tell them apart.
 *
 * @param problems every fault found, in reading order; empty means the ladder is sound
 * @param mediaIntact whether every segment and init segment the playlists name is present and has
 *     bytes in it — true even when the manifests describing them are unusable
 */
public record LadderReport(List<String> problems, boolean mediaIntact) {

    public LadderReport {
        problems = List.copyOf(problems);
    }

    public static LadderReport sound() {
        return new LadderReport(List.of(), true);
    }

    public boolean isSound() {
        return problems.isEmpty();
    }

    /** The faults as one line, for a log or a problem detail. */
    public String summary() {
        return String.join("; ", problems);
    }
}
