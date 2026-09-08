package com.azt.streaming.playback.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.azt.streaming.playback.domain.AssetNotFoundException;
import com.azt.streaming.playback.domain.HlsAsset;
import com.azt.streaming.playback.domain.HlsAssetLocator;
import com.azt.streaming.shared.config.StreamingProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Playback with the nginx offload disabled — this process writes the bytes.
 *
 * <p>This is the mode a bare {@code mvn spring-boot:run} uses, so it is the one that has to keep
 * working without any reverse proxy. The offload mode is covered by {@link PlaybackOffloadTest}.
 */
@WebMvcTest(PlaybackController.class)
// WebCorsConfiguration is a WebMvcConfigurer, so the slice instantiates it and needs the
// properties it binds; @WebMvcTest does not register @ConfigurationProperties on its own.
@EnableConfigurationProperties(StreamingProperties.class)
// @WebMvcTest scans controllers, not @Component collaborators.
@Import(HlsResponseFactory.class)
@ActiveProfiles("test")
class PlaybackControllerTest {

    private static final String VIDEO_ID = "2724a02c-f275-49d2-8389-e76bfeacd4c6";

    @TempDir static Path mediaRoot;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private HlsAssetLocator assetLocator;

    /** Writes a real file and describes it the way {@code FileSystemHlsAssetLocator} would. */
    private static HlsAsset asset(String fileName, byte[] bytes) throws IOException {
        Path file = mediaRoot.resolve(VIDEO_ID + "-" + fileName);
        Files.write(file, bytes);
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        return new HlsAsset(
                VIDEO_ID, fileName, file, attributes.size(), attributes.lastModifiedTime().toInstant());
    }

    @Test
    void servesTheMasterPlaylistAsAppleMpegurl() throws Exception {
        given(assetLocator.locate(VIDEO_ID, "master.m3u8"))
                .willReturn(asset("master.m3u8", "#EXTM3U\n".getBytes()));

        mockMvc.perform(get("/api/v1/stream/{id}/master.m3u8", VIDEO_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.apple.mpegurl"))
                .andExpect(content().string("#EXTM3U\n"));
    }

    @Test
    void servesTransportStreamSegmentsAsMp2t() throws Exception {
        given(assetLocator.locate(VIDEO_ID, "720p_000.ts")).willReturn(asset("720p_000.ts", new byte[] {0x47}));

        mockMvc.perform(get("/api/v1/stream/{id}/720p_000.ts", VIDEO_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentType("video/mp2t"));
    }

    @Test
    void servesFragmentedMp4SegmentsAsIsoSegment() throws Exception {
        // Previously fell through to application/octet-stream, which browsers refuse to append.
        given(assetLocator.locate(VIDEO_ID, "720p_000.m4s")).willReturn(asset("720p_000.m4s", new byte[] {0x00}));

        mockMvc.perform(get("/api/v1/stream/{id}/720p_000.m4s", VIDEO_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentType("video/iso.segment"));
    }

    @Test
    void servesTheFragmentedMp4InitSegmentAsMp4() throws Exception {
        // The CMAF ladder emits one of these per rung; without it a variant is unplayable.
        given(assetLocator.locate(VIDEO_ID, "720p_init.mp4")).willReturn(asset("720p_init.mp4", new byte[] {0x00}));

        mockMvc.perform(get("/api/v1/stream/{id}/720p_init.mp4", VIDEO_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentType("video/mp4"));
    }

    @Test
    void servesVariantPlaylistsAsAppleMpegurl() throws Exception {
        given(assetLocator.locate(VIDEO_ID, "720p.m3u8")).willReturn(asset("720p.m3u8", "#EXTM3U\n".getBytes()));

        mockMvc.perform(get("/api/v1/stream/{id}/720p.m3u8", VIDEO_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.apple.mpegurl"));
    }

    @Test
    void marksSegmentsImmutableAndPlaylistsShortLived() throws Exception {
        // A videoId is a fresh UUID per ingestion, so segment bytes under it never change. Saying so
        // is what lets a browser skip the revalidation round-trip entirely.
        given(assetLocator.locate(VIDEO_ID, "720p_000.m4s")).willReturn(asset("seg.m4s", new byte[] {0x00}));
        mockMvc.perform(get("/api/v1/stream/{id}/720p_000.m4s", VIDEO_ID))
                .andExpect(header().string("Cache-Control", containsString("immutable")))
                .andExpect(header().string("Cache-Control", containsString("max-age=31536000")));

        given(assetLocator.locate(VIDEO_ID, "master.m3u8")).willReturn(asset("m.m3u8", "#EXTM3U\n".getBytes()));
        mockMvc.perform(get("/api/v1/stream/{id}/master.m3u8", VIDEO_ID))
                .andExpect(header().string("Cache-Control", containsString("max-age=60")))
                .andExpect(header().string(
                        "Cache-Control", containsString("stale-while-revalidate=300")))
                .andExpect(header().string("Cache-Control", not(
                        containsString("immutable"))));
    }

    @Test
    void answersNotModifiedToAConditionalGetCarryingTheCurrentETag() throws Exception {
        HlsAsset segment = asset("cond.m4s", new byte[] {0x00, 0x01, 0x02});
        given(assetLocator.locate(VIDEO_ID, "720p_000.m4s")).willReturn(segment);

        String etag = mockMvc.perform(get("/api/v1/stream/{id}/720p_000.m4s", VIDEO_ID))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader("ETag");

        mockMvc.perform(get("/api/v1/stream/{id}/720p_000.m4s", VIDEO_ID).header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }

    @Test
    void honoursRangeRequests() throws Exception {
        // The docs listed "no HTTP Range support" as a known gap. Returning a Resource gets it from
        // Spring's message-converter path for free; this test is here so a well-meaning switch to
        // StreamingResponseBody cannot take it away silently.
        given(assetLocator.locate(VIDEO_ID, "720p_000.m4s"))
                .willReturn(asset("range.m4s", new byte[] {0, 1, 2, 3, 4, 5, 6, 7}));

        mockMvc.perform(get("/api/v1/stream/{id}/720p_000.m4s", VIDEO_ID).header("Range", "bytes=0-3"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 0-3/8"));

        mockMvc.perform(get("/api/v1/stream/{id}/720p_000.m4s", VIDEO_ID))
                .andExpect(header().string("Accept-Ranges", "bytes"));
    }

    @Test
    void returnsNotFoundForAnUnknownVideo() throws Exception {
        willThrow(new AssetNotFoundException(VIDEO_ID, "master.m3u8")).given(assetLocator).locate(any(), any());

        mockMvc.perform(get("/api/v1/stream/{id}/master.m3u8", VIDEO_ID)).andExpect(status().isNotFound());
    }

    @Test
    void forbidsCachingTheNotFoundThatMeansStillTranscoding() throws Exception {
        // A 404 on master.m3u8 is this service's readiness sentinel, and 404 is heuristically
        // cacheable. Without no-store, an intermediary can pin a video as unready for a client long
        // after it became playable — and polling never recovers, because the poll is what gets
        // served from cache.
        willThrow(new AssetNotFoundException(VIDEO_ID, "master.m3u8")).given(assetLocator).locate(any(), any());

        mockMvc.perform(get("/api/v1/stream/{id}/master.m3u8", VIDEO_ID))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", containsString("no-store")));
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
                .willReturn(asset("240p_012.ts", new byte[] {0x47}));

        mockMvc.perform(get("/api/v1/stream/{id}/240p_012.ts", VIDEO_ID)).andExpect(status().isOk());
    }
}
