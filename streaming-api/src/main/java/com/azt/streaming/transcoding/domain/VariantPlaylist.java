package com.azt.streaming.transcoding.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The parts of a variant playlist that anything downstream actually needs: the init segment, and
 * each media segment with the duration declared for it.
 *
 * <p>Extracted because two things were walking the same file for different reasons and only one of
 * them was doing it correctly. {@code VariantWeigher} needed durations and sizes and skipped every
 * line beginning with {@code #} — which quietly skipped {@code #EXT-X-MAP} too, so the one file a
 * variant cannot be decoded without was the one file it never looked at. A second hand-written
 * parser would have inherited that or invented its own gap.
 *
 * <p>A pure function of the lines, so a malformed playlist can be asserted without a filesystem.
 *
 * @param initSegment the {@code EXT-X-MAP} URI, absent for a playlist that declares none
 * @param segments media segments in playlist order
 * @param complete whether the playlist ends with {@code #EXT-X-ENDLIST}; a VOD playlist without it
 *     is one ffmpeg did not finish writing
 */
public record VariantPlaylist(Optional<String> initSegment, List<Segment> segments, boolean complete) {

    private static final String EXTINF = "#EXTINF:";
    private static final String MAP = "#EXT-X-MAP:";
    private static final String ENDLIST = "#EXT-X-ENDLIST";

    public VariantPlaylist {
        segments = List.copyOf(segments);
    }

    /**
     * @param durationSeconds what the playlist says this segment lasts
     * @param uri the segment's filename, relative to the playlist
     */
    public record Segment(double durationSeconds, String uri) {}

    public static VariantPlaylist parse(List<String> lines) {
        List<Segment> segments = new ArrayList<>();
        String initSegment = null;
        boolean complete = false;
        double pendingDuration = 0;

        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.startsWith(MAP)) {
                initSegment = attribute(trimmed, "URI");
                continue;
            }
            if (trimmed.startsWith(EXTINF)) {
                pendingDuration = duration(trimmed);
                continue;
            }
            if (trimmed.equals(ENDLIST)) {
                complete = true;
                continue;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            // A URI line only counts as a segment when an EXTINF introduced it. Anything else is a
            // playlist shape this does not understand, and inventing a zero-length segment for it
            // would make an unreadable file look merely empty.
            if (pendingDuration > 0) {
                segments.add(new Segment(pendingDuration, trimmed));
                pendingDuration = 0;
            }
        }
        return new VariantPlaylist(Optional.ofNullable(initSegment), segments, complete);
    }

    /**
     * Whether this playlist's segments need an {@code EXT-X-MAP} to be decodable.
     *
     * <p>fMP4 splits the header out of the media, so a rung whose segments are {@code .m4s} is
     * undecodable without its init segment. A WebVTT rendition has no such split — a subtitle
     * playlist naming a {@code .vtt} correctly declares no map, and demanding one of it reports a
     * fault in every healthy ladder this service writes.
     */
    public boolean needsInitSegment() {
        return segments.stream().anyMatch(segment -> {
            String uri = segment.uri().toLowerCase(java.util.Locale.ROOT);
            return uri.endsWith(".m4s") || uri.endsWith(".mp4");
        });
    }

    /** Every file this playlist cannot be played without, init segment first. */
    public List<String> referencedFiles() {
        List<String> files = new ArrayList<>(segments.size() + 1);
        initSegment.ifPresent(files::add);
        segments.forEach(segment -> files.add(segment.uri()));
        return List.copyOf(files);
    }

    public double totalSeconds() {
        return segments.stream().mapToDouble(Segment::durationSeconds).sum();
    }

    /** {@code #EXTINF:3.999978,} — the trailing comma is mandatory in the format and never useful. */
    private static double duration(String extinf) {
        String value = extinf.substring(EXTINF.length());
        int comma = value.indexOf(',');
        if (comma >= 0) {
            value = value.substring(0, comma);
        }
        try {
            return Double.parseDouble(value.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** {@code #EXT-X-MAP:URI="720p_init.mp4"} — quoted-string attributes, read by name. */
    private static String attribute(String tag, String name) {
        String needle = name + "=\"";
        int start = tag.indexOf(needle);
        if (start < 0) {
            return null;
        }
        int from = start + needle.length();
        int end = tag.indexOf('"', from);
        return end < 0 ? null : tag.substring(from, end);
    }
}
