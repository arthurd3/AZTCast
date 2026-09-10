package com.azt.streaming.transcoding.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Weighs a variant playlist's segments to find out what it actually costs to stream.
 *
 * <p>The master playlist used to advertise arithmetic on what the ladder <em>asked</em> for. That is
 * a reasonable approximation for a rate-controlled rung and a fiction for the two cases this
 * pipeline creates most often:
 *
 * <ul>
 *   <li>the copied top rung, whose declared figure was the source container's bitrate — audio track
 *       included, even though that audio now travels in its own rendition;
 *   <li>anything encoded with {@code libopenh264}, which warns on every run that it "can't be
 *       controlled ... without enabling skip frame" and then does not hit the target.
 * </ul>
 *
 * <p>BANDWIDTH is not decoration: a player picks a rung by comparing it to a measured throughput, so
 * a rung that claims 5 Mbps and delivers 8 is one a viewer on a 6 Mbps line will select and then
 * stall on. Reading the playlist's own {@code EXTINF} durations against the segment sizes on disk
 * answers it exactly, and costs a stat per segment.
 */
@Component
@Slf4j
public class VariantWeigher {

    /** Bits per byte. Spelled out because the alternative is an unexplained 8 in an expression. */
    private static final int BITS_PER_BYTE = 8;

    /**
     * The average and peak bitrate of a variant, or empty when the playlist cannot be read.
     *
     * @param playlist a variant {@code .m3u8} written by ffmpeg, alongside its segments
     */
    public Optional<Weight> weigh(Path playlist) {
        List<String> lines;
        try {
            lines = Files.readAllLines(playlist, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("Could not read {} to weigh it", playlist, e);
            return Optional.empty();
        }

        Path directory = playlist.getParent();
        long totalBytes = 0;
        double totalSeconds = 0;
        long peakBps = 0;
        double pendingDuration = 0;

        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.startsWith("#EXTINF:")) {
                pendingDuration = parseDuration(trimmed);
                continue;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#") || pendingDuration <= 0) {
                continue;
            }
            long bytes = sizeOf(directory.resolve(trimmed));
            if (bytes > 0) {
                totalBytes += bytes;
                totalSeconds += pendingDuration;
                peakBps = Math.max(peakBps, (long) (bytes * BITS_PER_BYTE / pendingDuration));
            }
            pendingDuration = 0;
        }

        if (totalSeconds <= 0 || totalBytes <= 0) {
            return Optional.empty();
        }
        int average = (int) Math.min(Integer.MAX_VALUE, (long) (totalBytes * BITS_PER_BYTE / totalSeconds));
        return Optional.of(new Weight(average, (int) Math.min(Integer.MAX_VALUE, peakBps)));
    }

    /** {@code #EXTINF:3.999978,} — the trailing comma is mandatory in the format and never useful. */
    private static double parseDuration(String extinf) {
        String value = extinf.substring("#EXTINF:".length());
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

    private static long sizeOf(Path segment) {
        try {
            return Files.size(segment);
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * @param averageBps mean bits per second across every segment
     * @param peakBps bits per second of the most expensive segment, which is what a player has to be
     *     able to sustain rather than the average it will see over a whole file
     */
    public record Weight(int averageBps, int peakBps) {}
}
