package com.azt.streaming.playback.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.azt.streaming.playback.domain.AssetNotFoundException;
import com.azt.streaming.playback.domain.HlsAssetLocator;
import com.azt.streaming.shared.config.StreamingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PlaybackController.class)
// WebCorsConfiguration is a WebMvcConfigurer, so the slice instantiates it and needs the
// properties it binds; @WebMvcTest does not register @ConfigurationProperties on its own.
@EnableConfigurationProperties(StreamingProperties.class)
@ActiveProfiles("test")
class PlaybackControllerTest {

    private static final String VIDEO_ID = "2724a02c-f275-49d2-8389-e76bfeacd4c6";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private HlsAssetLocator assetLocator;

    @Test
    void servesTheMasterPlaylistAsAppleMpegurl() throws Exception {
        given(assetLocator.locate(VIDEO_ID, "master.m3u8"))
                .willReturn(new ByteArrayResource("#EXTM3U\n".getBytes()));

        mockMvc.perform(get("/api/v1/stream/{id}/master.m3u8", VIDEO_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.apple.mpegurl"))
                .andExpect(content().string("#EXTM3U\n"));
    }

    @Test
    void servesTransportStreamSegmentsAsMp2t() throws Exception {
        given(assetLocator.locate(VIDEO_ID, "720p_000.ts"))
                .willReturn(new ByteArrayResource(new byte[] {0x47}));

        mockMvc.perform(get("/api/v1/stream/{id}/720p_000.ts", VIDEO_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentType("video/mp2t"));
    }

    @Test
    void servesFragmentedMp4SegmentsAsIsoSegment() throws Exception {
        // Previously fell through to application/octet-stream, which browsers refuse to append.
        given(assetLocator.locate(VIDEO_ID, "720p_000.m4s"))
                .willReturn(new ByteArrayResource(new byte[] {0x00}));

        mockMvc.perform(get("/api/v1/stream/{id}/720p_000.m4s", VIDEO_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentType("video/iso.segment"));
    }

    @Test
    void servesVariantPlaylistsAsAppleMpegurl() throws Exception {
        given(assetLocator.locate(VIDEO_ID, "720p.m3u8"))
                .willReturn(new ByteArrayResource("#EXTM3U\n".getBytes()));

        mockMvc.perform(get("/api/v1/stream/{id}/720p.m3u8", VIDEO_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.apple.mpegurl"));
    }

    @Test
    void returnsNotFoundForAnUnknownVideo() throws Exception {
        willThrow(new AssetNotFoundException(VIDEO_ID, "master.m3u8"))
                .given(assetLocator)
                .locate(any(), any());

        mockMvc.perform(get("/api/v1/stream/{id}/master.m3u8", VIDEO_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void doesNotServeAnythingOutsideTheMediaRoot() throws Exception {
        // The locator refuses anything that escapes; the controller must surface that as 404 and
        // never as a 200 or a 500.
        willThrow(new AssetNotFoundException("x", "y")).given(assetLocator).locate(any(), any());

        mockMvc.perform(get("/api/v1/stream/{id}/{file}", VIDEO_ID, "..%2F..%2Fetc%2Fpasswd"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/api/v1/stream/{id}/{file}", "..", "master.m3u8"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void passesThePathVariablesThroughUntouched() throws Exception {
        given(assetLocator.locate(eq(VIDEO_ID), eq("240p_012.ts")))
                .willReturn(new ByteArrayResource(new byte[] {0x47}));

        mockMvc.perform(get("/api/v1/stream/{id}/240p_012.ts", VIDEO_ID)).andExpect(status().isOk());
    }
}
