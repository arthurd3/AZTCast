package com.azt.streaming.transcoding.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.azt.streaming.transcoding.domain.HlsRendition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MasterPlaylistWriterTest {

    private static final List<HlsRendition> LADDER =
            List.of(
                    new HlsRendition("720p", 1280, 720, 3000, 128),
                    new HlsRendition("240p", 426, 240, 800, 128));

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
    void advertisesCodecsSoPlayersNeedNotProbeTheFirstSegment() {
        assertThat(writer.render(LADDER)).contains("CODECS=\"avc1.4d001f,mp4a.40.2\"");
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

        assertThat(lines).startsWith("#EXTM3U", "#EXT-X-VERSION:3");
        assertThat(lines.stream().filter(l -> l.startsWith("#EXT-X-STREAM-INF")).count()).isEqualTo(2);
        assertThat(lines.indexOf("720p.m3u8")).isLessThan(lines.indexOf("240p.m3u8"));
        assertThat(writer.render(LADDER)).contains("RESOLUTION=1280x720", "RESOLUTION=426x240");
    }

    @Test
    void writesTheMasterUnderTheVideoDirectory(@TempDir Path videoDirectory) throws IOException {
        writer.write(videoDirectory, LADDER);

        assertThat(videoDirectory.resolve("master.m3u8"))
                .exists()
                .content()
                .isEqualTo(writer.render(LADDER));
    }
}
