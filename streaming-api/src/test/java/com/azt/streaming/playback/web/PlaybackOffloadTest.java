package com.azt.streaming.playback.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.azt.streaming.playback.domain.HlsAsset;
import com.azt.streaming.playback.domain.HlsAssetLocator;
import com.azt.streaming.shared.config.StreamingProperties;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Playback with the nginx offload enabled — this process authorises, nginx writes the bytes.
 *
 * <p>Note what these tests do <em>not</em> need: a real file. That is the point of the mode. When
 * the response is a redirect header, nothing here opens the media directory, which is why a segment
 * no longer occupies a Tomcat thread for the length of its transfer.
 */
@WebMvcTest(PlaybackController.class)
@EnableConfigurationProperties(StreamingProperties.class)
@Import(HlsResponseFactory.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "aztcast.streaming.playback.offload-enabled=true")
class PlaybackOffloadTest {

    private static final String VIDEO_ID = "2724a02c-f275-49d2-8389-e76bfeacd4c6";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private HlsAssetLocator assetLocator;

    private static HlsAsset asset(String fileName) {
        return new HlsAsset(VIDEO_ID, fileName, Path.of("/var/lib/aztcast/hls", VIDEO_ID, fileName), 4096, Instant.EPOCH);
    }

    @Test
    void handsTheSegmentToNginxAndWritesNoBytesItself() throws Exception {
        given(assetLocator.locate(VIDEO_ID, "720p_000.m4s")).willReturn(asset("720p_000.m4s"));

        mockMvc.perform(get("/api/v1/stream/{id}/720p_000.m4s", VIDEO_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Accel-Redirect", "/_media/" + VIDEO_ID + "/720p_000.m4s"))
                .andExpect(content().string(""));
    }

    @Test
    void redirectsTheMasterPlaylistThroughTheSameInternalLocation() throws Exception {
        given(assetLocator.locate(VIDEO_ID, "master.m3u8")).willReturn(asset("master.m3u8"));

        mockMvc.perform(get("/api/v1/stream/{id}/master.m3u8", VIDEO_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Accel-Redirect", "/_media/" + VIDEO_ID + "/master.m3u8"));
    }

    @Test
    void stillDeclaresContentTypeAndCachePolicy() throws Exception {
        // nginx can derive the type from the extension, but only if its mime map is right. Sending
        // it from here means the answer does not depend on two configs agreeing.
        given(assetLocator.locate(VIDEO_ID, "720p_000.m4s")).willReturn(asset("720p_000.m4s"));

        mockMvc.perform(get("/api/v1/stream/{id}/720p_000.m4s", VIDEO_ID))
                .andExpect(content().contentType("video/iso.segment"))
                .andExpect(header().string("Cache-Control", containsString("immutable")));
    }

    @Test
    void leavesValidatorsToWhoeverWritesTheBytes() throws Exception {
        // nginx generates its own ETag/Last-Modified for the file it serves. Sending ours too would
        // hand the client a validator that the server answering its next revalidation never issued.
        given(assetLocator.locate(VIDEO_ID, "720p_000.m4s")).willReturn(asset("720p_000.m4s"));

        mockMvc.perform(get("/api/v1/stream/{id}/720p_000.m4s", VIDEO_ID))
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(header().doesNotExist("Last-Modified"));
    }
}
