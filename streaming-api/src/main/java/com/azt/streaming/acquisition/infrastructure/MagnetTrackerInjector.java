package com.azt.streaming.acquisition.infrastructure;

import bt.magnet.MagnetUri;
import com.azt.streaming.shared.config.StreamingProperties;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Adjusts a magnet's announce list before it is handed to the swarm: adds trackers worth asking,
 * removes ones that are not there any more.
 *
 * <p>A magnet's tracker list is whoever published it chose to include, and often that is nothing at
 * all — a bare {@code magnet:?xt=urn:btih:...} leaves DHT and peer exchange to discover the entire
 * swarm unaided. DHT gets there, but it gets there slowly, and on a thin torrent it may not get all
 * the way. Announcing to a handful of large public trackers as well costs one UDP round trip each
 * and typically returns peers before DHT has finished bootstrapping.
 *
 * <p>The list is configuration, not a lookup. Fetching a tracker list at ingestion time would send a
 * request whose timing names what is about to be downloaded, to a host with no reason to be told —
 * the same trade ADR-0016 refuses for the provenance map's geolocation.
 *
 * <p>The removals matter as much as the additions, and cost more when they are wrong. A public
 * magnet is copied from one indexer to the next for years and accumulates trackers that shut down
 * long ago — one real magnet carried {@code public.popcorn-tracker.org},
 * {@code tracker.coppersurfer.tk}, {@code torrent.gresille.org} and
 * {@code tracker.internetwarriors.net}, all dead for years and all still announced to. Each one
 * costs a full tracker timeout on every announce round, and the library collects peer sources
 * serially, so a handful of dead hosts delays the live ones behind them.
 *
 * <p>Pure apart from the injected lists, so the rebuild can be asserted without a network or a
 * runtime.
 */
@Component
public class MagnetTrackerInjector {

    private final List<String> extraTrackers;
    private final Set<String> deadHosts;

    @Autowired
    public MagnetTrackerInjector(StreamingProperties properties) {
        this(properties.torrent().extraTrackers(), properties.torrent().deadTrackers());
    }

    /** For tests, which have a list and no reason to build a whole properties tree around it. */
    MagnetTrackerInjector(List<String> extraTrackers, List<String> deadTrackers) {
        this.extraTrackers = extraTrackers.stream()
                .filter(url -> url != null && !url.isBlank())
                .map(String::trim)
                .toList();
        this.deadHosts = deadTrackers.stream()
                .filter(entry -> entry != null && !entry.isBlank())
                .map(MagnetTrackerInjector::hostOf)
                .filter(host -> !host.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * The same magnet with the configured trackers merged into its announce list.
     *
     * <p>Rebuilt rather than mutated because {@link MagnetUri} is immutable, which means every field
     * has to be carried across by hand: dropping the display name would cost the library the only
     * human-readable name it has before metadata arrives, and dropping the peer addresses would
     * discard peers the publisher handed us directly.
     *
     * <p>Deduplicated case-insensitively. The library would already drop exact repeats — its
     * builder collects trackers into a {@code HashSet} — but not two spellings of one URL, and a
     * magnet that names a tracker the configured list also names would otherwise be announced to it
     * twice. That same {@code HashSet} discards ordering, so there is no way from here to have the
     * magnet's own trackers asked first, however much one might want it.
     */
    public MagnetUri augment(MagnetUri parsed) {
        if (extraTrackers.isEmpty() && deadHosts.isEmpty()) {
            return parsed;
        }

        Set<String> seen = new LinkedHashSet<>();
        List<String> merged = new ArrayList<>();
        for (String tracker : concat(parsed.getTrackerUrls(), extraTrackers)) {
            if (tracker == null || tracker.isBlank() || isDead(tracker)) {
                continue;
            }
            if (seen.add(tracker.trim().toLowerCase(Locale.ROOT))) {
                merged.add(tracker.trim());
            }
        }

        MagnetUri.Builder builder = MagnetUri.torrentId(parsed.getTorrentId());
        parsed.getDisplayName().ifPresent(builder::name);
        merged.forEach(builder::tracker);
        parsed.getPeerAddresses().forEach(builder::peer);
        return builder.buildUri();
    }

    private boolean isDead(String trackerUrl) {
        return deadHosts.contains(hostOf(trackerUrl));
    }

    /**
     * The hostname out of an announce URL, lowercased, port and path discarded.
     *
     * <p>Matched on host rather than on the whole URL so one entry covers every spelling a magnet
     * might carry — {@code udp://x:6969/announce}, {@code udp://x:1337} and {@code http://x/announce}
     * are the same dead machine, and listing all three is a maintenance burden that guarantees one
     * gets missed.
     *
     * <p>Parsed by hand rather than with {@link java.net.URI}: announce URLs in the wild are
     * routinely malformed enough to throw, and a tracker whose URL will not parse is not one to
     * announce to either way.
     */
    private static String hostOf(String trackerUrl) {
        String value = trackerUrl.trim().toLowerCase(Locale.ROOT);
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        int end = value.length();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ':' || c == '/' || c == '?') {
                end = i;
                break;
            }
        }
        return value.substring(0, end);
    }

    private static List<String> concat(Iterable<String> first, List<String> second) {
        List<String> all = new ArrayList<>();
        first.forEach(all::add);
        all.addAll(second);
        return all;
    }
}
