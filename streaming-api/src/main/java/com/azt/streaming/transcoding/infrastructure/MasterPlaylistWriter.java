package com.azt.streaming.transcoding.infrastructure;

import com.azt.streaming.shared.storage.MediaStorage;
import com.azt.streaming.transcoding.domain.EncodedRendition;
import com.azt.streaming.transcoding.domain.HlsRendition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/** Writes the HLS master playlist that advertises the ladder to the player. */
@Component
public class MasterPlaylistWriter {

    /**
     * Version 7 because the variants are fMP4: {@code EXT-X-MAP} needs 6, and 7 is what ffmpeg
     * declares in the variants it writes. A master claiming 3 while pointing at version-7 variants
     * is inconsistent even where players tolerate it.
     */
    private static final int PLAYLIST_VERSION = 7;

    /**
     * Renders the master playlist for {@code renditions} into {@code videoDirectory}.
     *
     * <p>Variant URIs stay relative: hls.js resolves them against the master URL, so the API can be
     * mounted anywhere without rewriting playlists.
     */
    public String render(List<EncodedRendition> renditions) {
        StringBuilder playlist = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:")
                .append(PLAYLIST_VERSION)
                .append('\n')
                // Stated once at master level rather than left to each variant: it tells the player
                // every segment is independently decodable, which is what permits switching rungs.
                .append("#EXT-X-INDEPENDENT-SEGMENTS\n");

        for (EncodedRendition encoded : renditions) {
            HlsRendition rendition = encoded.rendition();
            playlist
                    .append("#EXT-X-STREAM-INF:BANDWIDTH=")
                    .append(rendition.peakBandwidthBps())
                    .append(",AVERAGE-BANDWIDTH=")
                    .append(rendition.averageBandwidthBps())
                    .append(",RESOLUTION=")
                    .append(rendition.resolution())
                    .append(",CODECS=\"")
                    .append(encoded.codecs())
                    .append("\"\n")
                    .append(rendition.playlistFileName())
                    .append('\n');
        }
        return playlist.toString();
    }

    public void write(Path videoDirectory, List<EncodedRendition> renditions) throws IOException {
        Files.writeString(videoDirectory.resolve(MediaStorage.MASTER_PLAYLIST), render(renditions));
    }
}
