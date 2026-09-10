package com.azt.streaming.transcoding.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides which rungs to build for a given source, and which of them can be copied.
 *
 * <p>The ladder used to be whatever was configured, encoded in full, every time. That was wrong in
 * both directions at once. Upward: a 480p torrent was scaled up to 1080p and 720p, which costs the
 * most encoding time of any rung on the ladder and produces a picture strictly worse than the
 * source it was invented from. Downward: a 1080p H.264 source — which is what most torrents are —
 * was decoded and re-encoded to produce a 1080p H.264 rung, losing a generation of quality to
 * arrive somewhere it already was.
 *
 * <p>So: never build a rung taller than the source, and when the source is already H.264, make the
 * top rung a copy of it. On a machine with no hardware H.264 encoder — which is the machine this
 * was written on, where VAAPI offers AV1 encoding and nothing else, and the distribution ships
 * {@code libopenh264} rather than {@code libx264} — copying is both the fastest path and the only
 * one that does not degrade the best rung.
 *
 * <p>A pure function, deliberately, so the whole rule table can be asserted without a video file.
 */
public final class LadderPlanner {

    /**
     * How close to the source height a configured rung may be before it is dropped as redundant.
     *
     * <p>Without it, an anamorphic source measuring 1082 pixels tall would keep the configured 1080p
     * rung and encode it directly beneath a 1082p copy of the same picture — two rungs a player can
     * never meaningfully choose between, one of which costs an encode.
     */
    private static final double NEAR_DUPLICATE_RATIO = 0.9;

    private LadderPlanner() {}

    /**
     * The rungs worth building for this source, best first.
     *
     * @param configured the full ladder from configuration, in any order
     * @param source what ffprobe found in the file about to be transcoded
     */
    public static List<PlannedRendition> plan(List<HlsRendition> configured, ProbedVideo source) {
        List<HlsRendition> byHeightDescending = configured.stream()
                .sorted(Comparator.comparingInt(HlsRendition::height).reversed())
                .toList();

        // Nothing measured means nothing to reason from — an output being probed, or a file whose
        // dimensions ffprobe would not report. Build what was configured, as before.
        if (source.height() <= 0) {
            return byHeightDescending.stream().map(PlannedRendition::encoded).toList();
        }

        boolean copying = source.videoIsCopyable();
        int ceiling = copying ? (int) Math.ceil(source.height() * NEAR_DUPLICATE_RATIO) : source.height() + 1;

        List<PlannedRendition> planned = new ArrayList<>();
        if (copying) {
            planned.add(copyRung(source));
        }
        byHeightDescending.stream()
                .filter(rung -> rung.height() < ceiling)
                .map(PlannedRendition::encoded)
                .forEach(planned::add);

        if (planned.isEmpty()) {
            // A source shorter than the shortest configured rung. Scaling up to it is the one
            // upscale worth doing: the alternative is a video with no playable rung at all.
            planned.add(PlannedRendition.encoded(byHeightDescending.get(byHeightDescending.size() - 1)));
        }
        return List.copyOf(planned);
    }

    /**
     * The source itself, as a rung.
     *
     * <p>The bitrate carried here is the container's, which already includes the audio track, so the
     * rung declares no separate audio bitrate — {@code peakBandwidthBps()} would otherwise count it
     * twice. What the playlist advertises is then what the file actually costs to stream, which is
     * the only honest figure available for a stream nothing rate-controlled.
     */
    private static PlannedRendition copyRung(ProbedVideo source) {
        HlsRendition rung = new HlsRendition(
                source.height() + "p", source.width(), source.height(), Math.max(source.bitRateKbps(), 1), 0);
        return new PlannedRendition(rung, true, source.audioIsCopyable());
    }
}
