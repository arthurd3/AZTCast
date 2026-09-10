package com.azt.streaming.ingestion.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.azt.streaming.ingestion.application.IngestionService;
import com.azt.streaming.ingestion.domain.RepairAction;
import com.azt.streaming.ingestion.domain.StreamJob;
import com.azt.streaming.ingestion.domain.StreamJobNotFoundException;
import com.azt.streaming.ingestion.domain.VideoNotRepairableException;
import com.azt.streaming.shared.config.StreamingProperties;
import com.azt.streaming.shared.storage.CatalogEntry;
import com.azt.streaming.shared.storage.VideoCatalog;
import com.azt.streaming.shared.storage.VideoIsKeptException;
import com.azt.streaming.shared.storage.VideoNotFoundException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({IngestionController.class, LegacyVideoController.class})
@EnableConfigurationProperties(StreamingProperties.class)
@ActiveProfiles("test")
class IngestionControllerTest {

    private static final String VIDEO_ID = "5f47b10e-d445-45df-bf17-9e0310c2012b";
    private static final String OLDER_ID = "2724a02c-f275-49d2-8389-e76bfeacd4c6";
    private static final String MAGNET = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567";
    private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");

    @Autowired private MockMvc mockMvc;

    @MockitoBean private IngestionService ingestionService;
    @MockitoBean private VideoCatalog videoCatalog;

    // ---------------------------------------------------------------- new API

    @Test
    void acceptsAMagnetAndReturnsTheVideoIdAsAField() throws Exception {
        given(ingestionService.startIngestion(MAGNET)).willReturn(StreamJob.downloading(VIDEO_ID, MAGNET, NOW));

        mockMvc.perform(json(post("/api/v1/videos")))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/videos/" + VIDEO_ID))
                .andExpect(jsonPath("$.videoId").value(VIDEO_ID))
                .andExpect(jsonPath("$.status").value("DOWNLOADING"));
    }

    @Test
    void reportsProgressAndTheStreamUrlOnceReady() throws Exception {
        given(ingestionService.findJob(VIDEO_ID))
                .willReturn(StreamJob.downloading(VIDEO_ID, MAGNET, NOW).ready(NOW));

        mockMvc.perform(get("/api/v1/videos/{id}", VIDEO_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.streamUrl").value("/api/v1/stream/" + VIDEO_ID + "/master.m3u8"));
    }

    @Test
    @DisplayName("lists in-flight ingestions so a refreshed page can pick them back up")
    void listsActiveJobs() throws Exception {
        given(ingestionService.listActiveJobs())
                .willReturn(
                        List.of(
                                StreamJob.downloading(VIDEO_ID, MAGNET, NOW).withProgress(64, NOW),
                                StreamJob.downloading(OLDER_ID, MAGNET, NOW).transcoding(NOW)));

        mockMvc.perform(get("/api/v1/videos/active"))
                .andExpect(status().isOk())
                // Never cached: the answer changes the moment an ingestion finishes.
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].videoId").value(VIDEO_ID))
                .andExpect(jsonPath("$[0].progressPercent").value(64))
                .andExpect(jsonPath("$[1].status").value("TRANSCODING"));
    }

    @Test
    @DisplayName("\"active\" is the listing, not a video id")
    void doesNotTreatActiveAsAVideoId() throws Exception {
        // Spring ranks a literal segment above a template one, so /active cannot be swallowed by
        // /{videoId}. Pinned because the two mappings are one refactor away from swapping order.
        given(ingestionService.listActiveJobs()).willReturn(List.of());

        mockMvc.perform(get("/api/v1/videos/active")).andExpect(status().isOk());

        org.mockito.Mockito.verify(ingestionService, org.mockito.Mockito.never()).findJob(any());
    }

    @Test
    void reportsFailuresWithAReason() throws Exception {
        given(ingestionService.findJob(VIDEO_ID))
                .willReturn(StreamJob.downloading(VIDEO_ID, MAGNET, NOW).failed("No video file found", NOW));

        mockMvc.perform(get("/api/v1/videos/{id}", VIDEO_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("No video file found"))
                .andExpect(jsonPath("$.streamUrl").doesNotExist());
    }

    @Test
    void returnsProblemDetailForAnUnknownJob() throws Exception {
        given(ingestionService.findJob(any())).willThrow(new StreamJobNotFoundException("nope"));

        mockMvc.perform(get("/api/v1/videos/{id}", "nope"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://aztcast.dev/problems/job-not-found"));
    }

    @Test
    void rejectsABlankMagnetUrl() throws Exception {
        mockMvc.perform(
                        post("/api/v1/videos")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"magnetUrl\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("magnetUrl"));
    }

    // ----------------------------------------------------------- the library

    @Test
    void listsWatchableVideos() throws Exception {
        given(videoCatalog.list())
                .willReturn(
                        List.of(
                                new CatalogEntry(
                                        VIDEO_ID,
                                        "Movie.2024.1080p.mkv",
                                        NOW,
                                        List.of("1080p", "720p"),
                                        12_345L,
                                        true,
                                        false),
                                new CatalogEntry(
                                        OLDER_ID, null, NOW.minusSeconds(60), List.of("720p"), 99L, false, false)));

        mockMvc.perform(get("/api/v1/videos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].videoId").value(VIDEO_ID))
                .andExpect(jsonPath("$[0].title").value("Movie.2024.1080p.mkv"))
                .andExpect(jsonPath("$[0].streamUrl").value("/api/v1/stream/" + VIDEO_ID + "/master.m3u8"))
                .andExpect(jsonPath("$[0].qualities[0]").value("1080p"))
                .andExpect(jsonPath("$[0].sizeBytes").value(12_345L))
                .andExpect(jsonPath("$[0].posterUrl").value("/api/v1/stream/" + VIDEO_ID + "/poster.jpg"))
                // No poster means no thumbnail to draw. Omitted like the title, so the player can
                // fall back to a placeholder rather than requesting an image that 404s.
                .andExpect(jsonPath("$[1].posterUrl").doesNotExist())
                // Everything transcoded before the sidecar existed has no name to give. The field is
                // omitted rather than sent as null, so a client can tell "unnamed" from "named the
                // empty string" — and the player falls back to the id for exactly this case.
                .andExpect(jsonPath("$[1].title").doesNotExist());
    }

    @Test
    @DisplayName("the library is never cached — its whole job is to show a video the moment it lands")
    void doesNotCacheTheListing() throws Exception {
        given(videoCatalog.list()).willReturn(List.of());

        mockMvc.perform(get("/api/v1/videos"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$").isEmpty());
    }

    // ------------------------------------------------------------- legacy API

    @Test
    void legacyEndpointReturnsItsPortugueseSentenceByteForByte() throws Exception {
        given(ingestionService.startIngestion(MAGNET)).willReturn(StreamJob.downloading(VIDEO_ID, MAGNET, NOW));

        // An out-of-repo Python bot regexes the videoId out of this prose. Changing so much as a
        // space or an accent hands it a 202 it cannot parse — a silent failure in another
        // repository. This assertion is the contract; do not "tidy" the expected string.
        String expected =
                "Download iniciado. O stream estará disponível em: /api/v1/stream/" + VIDEO_ID + "/master.m3u8";

        mockMvc.perform(json(post("/api/v1/video/download")))
                .andExpect(status().isAccepted())
                .andExpect(content().string(expected))
                .andExpect(result ->
                        org.assertj.core.api.Assertions.assertThat(
                                        result.getResponse().getContentAsByteArray())
                                .isEqualTo(expected.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void legacyEndpointAdvertisesItsSuccessorWithoutBreakingTheBody() throws Exception {
        given(ingestionService.startIngestion(MAGNET)).willReturn(StreamJob.downloading(VIDEO_ID, MAGNET, NOW));

        mockMvc.perform(json(post("/api/v1/video/download")))
                .andExpect(header().string("Location", "/api/v1/videos/" + VIDEO_ID))
                .andExpect(header().string("Deprecation", "true"))
                .andExpect(header().exists("Sunset"))
                .andExpect(header().string("Link", "</api/v1/videos>; rel=\"successor-version\""));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder json(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
        return builder
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"magnetUrl\":\"" + MAGNET + "\"}");
    }

    @Test
    @DisplayName("keeps a video, and stops keeping it")
    void keepAndUnkeep() throws Exception {
        given(videoCatalog.setKept(VIDEO_ID, true)).willReturn(true);
        given(videoCatalog.setKept(VIDEO_ID, false)).willReturn(true);

        mockMvc.perform(put("/api/v1/videos/" + VIDEO_ID + "/keep")).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/videos/" + VIDEO_ID + "/keep")).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("keeping a video that no longer exists is a problem document, not a blank 404")
    void keepingAReapedVideoIsANotFoundProblem() throws Exception {
        given(videoCatalog.setKept(VIDEO_ID, true)).willReturn(false);

        mockMvc.perform(put("/api/v1/videos/" + VIDEO_ID + "/keep"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                // Distinct from job-not-found: a job expiring while its media lives is normal, and
                // telling the two apart is the difference between "retry" and "it is gone".
                .andExpect(jsonPath("$.type").value("https://aztcast.dev/problems/video-not-found"));
    }

    @Test
    @DisplayName("the listing says whether each video is kept")
    void theListingCarriesTheKeptFlag() throws Exception {
        given(videoCatalog.list())
                .willReturn(List.of(
                        new CatalogEntry(VIDEO_ID, "Kept.mkv", NOW, List.of("720p"), 1L, true, true),
                        new CatalogEntry(OLDER_ID, "Ordinary.mkv", NOW, List.of("720p"), 1L, true, false)));

        // A primitive, so it is always present: the library renders a toggle from it, and an absent
        // field would leave that control with no state to show.
        mockMvc.perform(get("/api/v1/videos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kept").value(true))
                .andExpect(jsonPath("$[1].kept").value(false));
    }

    // ---------------------------------------------------------------- delete

    @Test
    @DisplayName("deletes a video, and does not ask twice about one nobody kept")
    void deletesAVideo() throws Exception {
        mockMvc.perform(delete("/api/v1/videos/" + VIDEO_ID)).andExpect(status().isNoContent());

        then(ingestionService).should().delete(VIDEO_ID, false);
    }

    @Test
    @DisplayName("a kept video answers 409, and says how to mean it")
    void deletingAKeptVideoConflicts() throws Exception {
        willThrow(new VideoIsKeptException(VIDEO_ID)).given(ingestionService).delete(VIDEO_ID, false);

        mockMvc.perform(delete("/api/v1/videos/" + VIDEO_ID))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://aztcast.dev/problems/video-is-kept"))
                // The detail names the way out. A 409 that does not is a dead end for anyone
                // holding only the response.
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("force=true")));
    }

    @Test
    @DisplayName("force=true reaches the service as force")
    void forceIsCarriedThrough() throws Exception {
        mockMvc.perform(delete("/api/v1/videos/" + VIDEO_ID + "?force=true")).andExpect(status().isNoContent());

        then(ingestionService).should().delete(VIDEO_ID, true);
    }

    @Test
    @DisplayName("deleting a video that is not there is a problem document, not a blank 404")
    void deletingAnUnknownVideoIsANotFoundProblem() throws Exception {
        willThrow(new VideoNotFoundException(VIDEO_ID)).given(ingestionService).delete(VIDEO_ID, false);

        mockMvc.perform(delete("/api/v1/videos/" + VIDEO_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://aztcast.dev/problems/video-not-found"));
    }

    @Test
    @DisplayName("the keep sub-resource is not shadowed by the delete route")
    void unkeepStillRoutesToTheSubResource() throws Exception {
        // Two DELETEs under one collection, one a suffix of the other. If Spring ever preferred
        // /{videoId} for /{videoId}/keep, un-keeping a video would silently delete it instead.
        given(videoCatalog.setKept(VIDEO_ID, false)).willReturn(true);

        mockMvc.perform(delete("/api/v1/videos/" + VIDEO_ID + "/keep")).andExpect(status().isNoContent());

        then(ingestionService).should(org.mockito.Mockito.never()).delete(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    // ---------------------------------------------------------------- repair

    @Test
    @DisplayName("200 and no Location when there was nothing wrong")
    void repairingASoundVideoAnswersOk() throws Exception {
        given(ingestionService.repair(VIDEO_ID)).willReturn(RepairAction.NOTHING_TO_DO);

        mockMvc.perform(post("/api/v1/videos/" + VIDEO_ID + "/repair"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoId").value(VIDEO_ID))
                .andExpect(jsonPath("$.action").value("NOTHING_TO_DO"))
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    @DisplayName("202 and a Location pointing at the job, for work that takes time")
    void repairingABrokenVideoAnswersAccepted() throws Exception {
        given(ingestionService.repair(VIDEO_ID)).willReturn(RepairAction.MANIFESTS_REBUILT);

        mockMvc.perform(post("/api/v1/videos/" + VIDEO_ID + "/repair"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/videos/" + VIDEO_ID))
                // The id is unchanged, which is the whole point: the link a viewer already has
                // keeps working rather than being replaced by a second video.
                .andExpect(jsonPath("$.videoId").value(VIDEO_ID))
                .andExpect(jsonPath("$.action").value("MANIFESTS_REBUILT"))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    @DisplayName("422 when the video is broken and there is nothing left to rebuild it from")
    void refusesAVideoWithNoSourceLeft() throws Exception {
        given(ingestionService.repair(VIDEO_ID))
                .willThrow(new VideoNotRepairableException(VIDEO_ID, "no magnet was recorded for it"));

        mockMvc.perform(post("/api/v1/videos/" + VIDEO_ID + "/repair"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://aztcast.dev/problems/not-repairable"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("no magnet")));
    }

    @Test
    void repairingSomethingThatIsNotThereIsA404() throws Exception {
        given(ingestionService.repair(VIDEO_ID)).willThrow(new VideoNotFoundException(VIDEO_ID));

        mockMvc.perform(post("/api/v1/videos/" + VIDEO_ID + "/repair")).andExpect(status().isNotFound());
    }
}
