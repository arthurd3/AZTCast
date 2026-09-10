package com.azt.streaming.transcoding.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;

import com.azt.streaming.support.PropertiesFixture;
import com.azt.streaming.transcoding.domain.FfmpegSupport;
import com.azt.streaming.transcoding.domain.TranscodingException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FfmpegCapabilitiesTest {

    /**
     * Captured verbatim from Fedora's patent-free build — the one that shipped the defect. Note
     * {@code libopenh264} rather than {@code libx264}, and that the codec an implementation serves
     * is only in the description.
     */
    private static final String ENCODERS =
            """
            Encoders:
             V..... = Video
             A..... = Audio
             .....D = Supports direct rendering method 1
             ------
             V....D libopenh264          OpenH264 H.264 / AVC / MPEG-4 AVC (codec h264)
             V....D h264_vaapi           H.264/AVC (VAAPI) (codec h264)
             A....D aac                  AAC (Advanced Audio Coding)
             A....D libfdk_aac           Fraunhofer FDK AAC (codec aac)
             A....D ac3                  ATSC A/52A (AC-3)
            """;

    /** The same build's decoders. There is an {@code ac3} but no {@code eac3} — that is the bug. */
    private static final String DECODERS =
            """
            Decoders:
             V..... = Video
             ------
             V....D libopenh264          OpenH264 H.264 / AVC / MPEG-4 AVC (codec h264)
             A....D aac                  AAC (Advanced Audio Coding)
             A....D libfdk_aac           Fraunhofer FDK AAC (codec aac)
             A....D ac3                  ATSC A/52A (AC-3)
             A....D eatgq                Electronic Arts TGQ video
            """;

    private static final String HWACCELS =
            """
            Hardware acceleration methods:
            vaapi
            vulkan

            """;

    @Mock private ProcessRunner processRunner;

    private FfmpegCapabilities capabilities() {
        return new FfmpegCapabilities(PropertiesFixture.defaults().build(), processRunner);
    }

    private void stubListings() {
        given(processRunner.runCapturing(argThat(command -> command != null && command.contains("-encoders")), any())).willReturn(ENCODERS);
        given(processRunner.runCapturing(argThat(command -> command != null && command.contains("-decoders")), any())).willReturn(DECODERS);
        given(processRunner.runCapturing(argThat(command -> command != null && command.contains("-hwaccels")), any())).willReturn(HWACCELS);
    }

    @Test
    @DisplayName("indexes both the implementation and the codec it serves")
    void readsBothNames() {
        stubListings();
        FfmpegSupport support = capabilities().support();

        assertThat(support.known()).isTrue();
        // Asking only about implementations would conclude this build cannot decode H.264 while it
        // is decoding H.264.
        assertThat(support.canDecode("h264")).isTrue();
        assertThat(support.canEncode("libopenh264")).isTrue();
        assertThat(support.canEncode("libx264")).isFalse();
        assertThat(support.canEncode("aac")).isTrue();
        assertThat(support.hwaccels()).containsExactlyInAnyOrder("vaapi", "vulkan");
    }

    @Test
    @DisplayName("sees the missing E-AC-3 decoder that cost a 756 MB download")
    void seesTheMissingDecoder() {
        stubListings();
        FfmpegSupport support = capabilities().support();

        assertThat(support.canDecode("ac3")).isTrue();
        assertThat(support.canDecode("eac3")).isFalse();
        // eatgq starts with "ea" and is a video codec; a substring match would have called it a hit.
        assertThat(support.canDecode("eatgq")).isTrue();
    }

    @Test
    void picksTheFirstPreferenceThisBuildActuallyHas() {
        stubListings();

        assertThat(capabilities().firstEncoder(List.of("libx264", "libopenh264"))).contains("libopenh264");
        assertThat(capabilities().firstEncoder(List.of("libx264", "h264_nvenc"))).isEmpty();
    }

    @Test
    @DisplayName("reads the listings once, however many questions are asked")
    void memoisesTheListings() {
        stubListings();
        FfmpegCapabilities capabilities = capabilities();

        capabilities.support();
        capabilities.support();
        capabilities.firstEncoder(List.of("libopenh264"));

        // Three commands: encoders, decoders, hwaccels. Not nine.
        org.mockito.BDDMockito.then(processRunner).should(org.mockito.Mockito.times(3)).runCapturing(any(), any());
    }

    @Test
    @DisplayName("an unreadable listing answers yes to everything rather than no to everything")
    void unknownIsPermissive() {
        // /bin/true in the test suite, a hung binary in the field, a future ffmpeg that reformats
        // the table. Refusing every file over any of those would be far worse than the status quo.
        given(processRunner.runCapturing(any(), any())).willThrow(new TranscodingException("boom"));
        FfmpegSupport support = capabilities().support();

        assertThat(support.known()).isFalse();
        assertThat(support.canDecode("anything-at-all")).isTrue();
        assertThat(support.canEncode("libx264")).isTrue();
        assertThat(support.firstEncoder(List.of("libx264", "libopenh264"))).contains("libx264");
    }

    @Test
    void treatsAnEmptyListingAsUnreadableRatherThanAsAnEmptyBuild() {
        given(processRunner.runCapturing(any(), any())).willReturn("");

        assertThat(capabilities().support().known()).isFalse();
    }

    @Test
    void survivesANullFromTheProcessRunner() {
        // A mocked runner returns null, and so does a binary that exits 0 having printed nothing.
        given(processRunner.runCapturing(any(), any())).willReturn(null);

        assertThat(capabilities().support().known()).isFalse();
    }
}
