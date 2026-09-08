package com.azt.streaming.transcoding.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.azt.streaming.transcoding.domain.EncodedRendition;
import com.azt.streaming.transcoding.domain.HlsRendition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MasterPlaylistWriterTest {

    // Two rungs whose measured codec strings differ — which is the whole point: a single hardcoded
    // value was correct only for 720p.
    private static final List<EncodedRendition> LADDER = List.of(
            new EncodedRendition(new HlsRendition("720p", 1280, 720, 3000, 128), "avc1.4d001f,mp4a.40.2"),
            new EncodedRendition(new HlsRendition("240p", 426, 240, 800, 128), "avc1.4d0015,mp4a.40.2"));

    private final MasterPlaylistWriter writer = new MasterPlaylistWriter();

    @Test
    void bandwidthAccountsForTheAudioTrack() {
        // The old playlist advertised 3000000 — the video bitrate alone, with the 128 kbps audio
        // track ignored and the target reported instead of the peak.
        assertThat(writer.render(LADDER))
                .contains("BANDWIDTH=3338000") // (3000 * 1.07) + 128, in bps
                .contains("AVERAGE-BANDWIDTH=3128000") // 3000 + 128, in bps
                .doesNotContain("BANDWIDTH=3000000");
    }

    @Test
    void advertisesTheCodecsMeasuredForEachRungRatherThanOneConstant() {
        // 240p really does encode to level 2.1, not 3.1. Advertising 3.1 for it — as a single
        // hardcoded string did — tells the player to provision a decoder for a stream it will
        // never receive, and mis-sizes its capability check.
        String playlist = writer.render(LADDER);

        assertThat(playlist).contains("CODECS=\"avc1.4d001f,mp4a.40.2\"");
        assertThat(playlist).contains("CODECS=\"avc1.4d0015,mp4a.40.2\"");
    }

    @Test
    void keepsVariantUrisRelativeToTheMaster() {
        // hls.js resolves these against the master URL, so absolute paths would pin the API to one
        // mount point.
        assertThat(writer.render(LADDER).lines())
                .contains("720p.m3u8", "240p.m3u8")
                .noneMatch(line -> line.startsWith("/") || line.startsWith("http"));
    }

    @Test
    void emitsOneStreamInfPerRungInLadderOrder() {
        List<String> lines = writer.render(LADDER).lines().toList();

        // Version 7, not 3: the variants are fMP4 and use EXT-X-MAP.
        assertThat(lines).startsWith("#EXTM3U", "#EXT-X-VERSION:7", "#EXT-X-INDEPENDENT-SEGMENTS");
        assertThat(lines.stream().filter(l -> l.startsWith("#EXT-X-STREAM-INF")).count()).isEqualTo(2);
        assertThat(lines.indexOf("720p.m3u8")).isLessThan(lines.indexOf("240p.m3u8"));
        assertThat(writer.render(LADDER)).contains("RESOLUTION=1280x720", "RESOLUTION=426x240");
    }

    @Test
    void writesTheMasterUnderTheVideoDirectory(@TempDir Path videoDirectory) throws IOException {
        writer.write(videoDirectory, LADDER);

        assertThat(videoDirectory.resolve("master.m3u8")).exists().content().isEqualTo(writer.render(LADDER));
    }
}
