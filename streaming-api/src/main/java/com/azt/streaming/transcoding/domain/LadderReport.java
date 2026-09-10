package com.azt.streaming.transcoding.domain;

import java.util.List;
import java.util.Optional;

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
public record LadderReport(List<String> problems, boolean mediaIntact, Optional<PublishedAudio> audio) {

    public LadderReport {
        problems = List.copyOf(problems);
    }

    public static LadderReport sound() {
        return new LadderReport(List.of(), true, Optional.empty());
    }

    public LadderReport(List<String> problems, boolean mediaIntact) {
        this(problems, mediaIntact, Optional.empty());
    }

    /**
     * The audio rendition as the published master describes it.
     *
     * <p>Read back so a repair can tell whether this build could do better than what is on disk
     * now. A ladder published without a decoder carries an untouched {@code ec-3} track; the same
     * host with a decoder installed would produce AAC, and nothing else in the system would ever
     * notice the difference.
     *
     * @param codecs the RFC 6381 identifier the master advertises for it
     * @param channels the {@code CHANNELS} attribute, 0 when the master does not say
     */
    public record PublishedAudio(String codecs, int channels) {}

    /**
     * Whether {@code candidate} would be an improvement on what is published.
     *
     * <p>Codec first, then channel count — a video transcoded before the audio policy preserved
     * layouts carries a stereo fold of a 5.1 track, and that is worth rebuilding too.
     */
    public boolean audioWouldImproveTo(String codecs, int channels) {
        return audio.map(published -> !published.codecs().equalsIgnoreCase(codecs)
                        || published.channels() > 0 && channels > published.channels())
                .orElse(false);
    }

    public boolean isSound() {
        return problems.isEmpty();
    }

    /** The faults as one line, for a log or a problem detail. */
    public String summary() {
        return String.join("; ", problems);
    }
}
