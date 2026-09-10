package com.azt.streaming.transcoding.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VariantWeigherTest {

    private final VariantWeigher weigher = new VariantWeigher();

    @Test
    @DisplayName("weighs the segments rather than trusting what the ladder asked for")
    void measuresAverageAndPeak(@TempDir Path directory) throws IOException {
        // Two 4-second segments: one 4 KB, one 8 KB. The average is 12 KB over 8 s = 12000 bps;
        // the peak is the expensive one, 8 KB over 4 s = 16000 bps. A player has to sustain the
        // peak, not the average, which is why BANDWIDTH and AVERAGE-BANDWIDTH are different numbers.
        Files.write(directory.resolve("720p_000.m4s"), new byte[4000]);
        Files.write(directory.resolve("720p_001.m4s"), new byte[8000]);
        Path playlist = directory.resolve("720p.m3u8");
        Files.writeString(
                playlist,
                """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-MAP:URI="720p_init.mp4"
                #EXTINF:4.000000,
                720p_000.m4s
                #EXTINF:4.000000,
                720p_001.m4s
                #EXT-X-ENDLIST
                """);

        VariantWeigher.Weight weight = weigher.weigh(playlist).orElseThrow();

        assertThat(weight.averageBps()).isEqualTo(12_000);
        assertThat(weight.peakBps()).isEqualTo(16_000);
    }

    @Test
    @DisplayName("uses each segment's own duration, not an assumed one")
    void honoursTheFinalShortSegment(@TempDir Path directory) throws IOException {
        // The last segment of any real encode is short. Dividing it by the target duration would
        // understate its bitrate and could hide the most expensive part of the file.
        Files.write(directory.resolve("a_000.m4s"), new byte[4000]);
        Files.write(directory.resolve("a_001.m4s"), new byte[2000]);
        Path playlist = directory.resolve("a.m3u8");
        Files.writeString(
                playlist,
                """
                #EXTM3U
                #EXTINF:4.000000,
                a_000.m4s
                #EXTINF:0.500000,
                a_001.m4s
                #EXT-X-ENDLIST
                """);

        VariantWeigher.Weight weight = weigher.weigh(playlist).orElseThrow();

        // 2000 bytes in half a second is 32000 bps — nearly four times the other segment's rate.
        assertThat(weight.peakBps()).isEqualTo(32_000);
        assertThat(weight.averageBps()).isEqualTo(10_666);
    }

    @Test
    void answersEmptyRatherThanZeroWhenItCannotWeighAnything(@TempDir Path directory) throws IOException {
        // Empty rather than zero: the caller falls back to what the ladder asked for, and a
        // BANDWIDTH of 0 in a manifest makes a player pick that rung first, every time.
        assertThat(weigher.weigh(directory.resolve("missing.m3u8"))).isEmpty();

        Path headerOnly = directory.resolve("empty.m3u8");
        Files.writeString(headerOnly, "#EXTM3U\n#EXT-X-ENDLIST\n");
        assertThat(weigher.weigh(headerOnly)).isEmpty();
    }

    @Test
    void ignoresSegmentsThatAreNotOnDisk(@TempDir Path directory) throws IOException {
        Files.write(directory.resolve("b_000.m4s"), new byte[4000]);
        Path playlist = directory.resolve("b.m3u8");
        Files.writeString(
                playlist,
                """
                #EXTM3U
                #EXTINF:4.000000,
                b_000.m4s
                #EXTINF:4.000000,
                b_001.m4s
                #EXT-X-ENDLIST
                """);

        assertThat(weigher.weigh(playlist).orElseThrow().averageBps()).isEqualTo(8_000);
    }
}
