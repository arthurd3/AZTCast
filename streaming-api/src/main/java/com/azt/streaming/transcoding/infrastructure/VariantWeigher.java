package com.azt.streaming.transcoding.infrastructure;

import com.azt.streaming.transcoding.domain.VariantPlaylist;
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
        VariantPlaylist parsed = VariantPlaylist.parse(lines);
        long totalBytes = 0;
        double totalSeconds = 0;
        long peakBps = 0;

        for (VariantPlaylist.Segment segment : parsed.segments()) {
            long bytes = sizeOf(directory.resolve(segment.uri()));
            if (bytes > 0) {
                totalBytes += bytes;
                totalSeconds += segment.durationSeconds();
                peakBps = Math.max(peakBps, (long) (bytes * BITS_PER_BYTE / segment.durationSeconds()));
            }
        }

        if (totalSeconds <= 0 || totalBytes <= 0) {
            return Optional.empty();
        }
        int average = (int) Math.min(Integer.MAX_VALUE, (long) (totalBytes * BITS_PER_BYTE / totalSeconds));
        return Optional.of(new Weight(average, (int) Math.min(Integer.MAX_VALUE, peakBps)));
    }

    /**
     * A segment's size, or 0 for one that is not there.
     *
     * <p>Weighing tolerates a missing segment because its job is a bandwidth estimate, not a
     * verdict. Deciding whether the ladder is intact belongs to {@link LadderIntegrity}, which runs
     * first and refuses to publish anything this would have to paper over.
     */
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
