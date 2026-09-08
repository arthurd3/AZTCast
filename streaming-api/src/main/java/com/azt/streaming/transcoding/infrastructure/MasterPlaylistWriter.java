package com.azt.streaming.transcoding.infrastructure;

import com.azt.streaming.transcoding.domain.HlsRendition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/** Writes the HLS master playlist that advertises the ladder to the player. */
@Component
public class MasterPlaylistWriter {

    public static final String MASTER_PLAYLIST = "master.m3u8";

    /**
     * Renders the master playlist for {@code renditions} into {@code videoDirectory}.
     *
     * <p>Variant URIs stay relative: hls.js resolves them against the master URL, so the API can be
     * mounted anywhere without rewriting playlists.
     */
    public String render(List<HlsRendition> renditions) {
        StringBuilder playlist = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n");
        for (HlsRendition rendition : renditions) {
            playlist
                    .append("#EXT-X-STREAM-INF:BANDWIDTH=")
                    .append(rendition.peakBandwidthBps())
                    .append(",AVERAGE-BANDWIDTH=")
                    .append(rendition.averageBandwidthBps())
                    .append(",RESOLUTION=")
                    .append(rendition.resolution())
                    .append(",CODECS=\"")
                    .append(rendition.codecs())
                    .append("\"\n")
                    .append(rendition.playlistFileName())
                    .append('\n');
        }
        return playlist.toString();
    }

    public void write(Path videoDirectory, List<HlsRendition> renditions) throws IOException {
        Files.writeString(videoDirectory.resolve(MASTER_PLAYLIST), render(renditions));
    }
}
