package com.azt.streaming.transcoding.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.azt.streaming.support.PropertiesFixture;
import com.azt.streaming.transcoding.domain.ProbedVideo;
import com.azt.streaming.transcoding.domain.TranscodingException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FfprobeMediaProbeTest {

    /**
     * Captured verbatim from a real run against a 720p variant playlist produced by this project's
     * own ffmpeg command. Note the top-level {@code streams} array is duplicated under
     * {@code programs} — parsing the wrong one would double-count the audio track.
     */
    private static final String REAL_720P_OUTPUT =
            """
            {
                "programs": [ { "streams": [
                    { "profile": "Main", "codec_type": "video", "level": 31 },
                    { "profile": "LC", "codec_type": "audio" } ] } ],
                "stream_groups": [],
                "streams": [
                    { "profile": "Main", "codec_type": "video", "level": 31 },
                    { "profile": "LC", "codec_type": "audio" }
                ]
            }
            """;

    /** Same command against the 240p rung of the same encode. */
    private static final String REAL_240P_OUTPUT =
            """
            { "programs": [], "stream_groups": [], "streams": [
                { "profile": "Main", "codec_type": "video", "level": 21 },
                { "profile": "LC", "codec_type": "audio" } ] }
            """;

    /** What an init segment alone reports — no profile, and level -99 meaning "unknown". */
    private static final String INIT_SEGMENT_ONLY =
            """
            { "programs": [], "stream_groups": [], "streams": [
                { "codec_type": "video", "level": -99 },
                { "codec_type": "audio" } ] }
            """;

    @Mock private ProcessRunner processRunner;

    private FfprobeMediaProbe probe() {
        return new FfprobeMediaProbe(PropertiesFixture.defaults().build(), processRunner);
    }

    @Test
    void readsProfileAndLevelFromRealFfprobeOutput() {
        given(processRunner.runCapturing(any(), any())).willReturn(REAL_720P_OUTPUT);

        ProbedVideo probed = probe().probe(Path.of("720p.m3u8"));

        assertThat(probed.hasVideo()).isTrue();
        assertThat(probed.hasAudio()).isTrue();
        assertThat(probed.videoProfile()).isEqualTo("Main");
        assertThat(probed.videoLevel()).isEqualTo(31);
        assertThat(probed.codecs()).isEqualTo("avc1.4d001f,mp4a.40.2");
    }

    @Test
    void distinguishesRungsThatTheOldHardcodedStringConflated() {
        given(processRunner.runCapturing(any(), any())).willReturn(REAL_240P_OUTPUT);

        // The ladder asks every rung for Main; 240p still comes out at level 2.1 because the level
        // follows resolution and bitrate. This is the measurement the master playlist now carries.
        assertThat(probe().probe(Path.of("240p.m3u8")).codecs()).isEqualTo("avc1.4d0015,mp4a.40.2");
    }

    @Test
    void queriesTheVariantPlaylistQuietlyAndAsJson() {
        List<String> command = probe().command(Path.of("/out/vid/720p.m3u8"));

        assertThat(command).containsSubsequence("-v", "error").containsSubsequence("-of", "json");
        assertThat(command.getFirst()).isEqualTo("/bin/true"); // the configured probe binary
        assertThat(command.getLast()).isEqualTo("/out/vid/720p.m3u8");
    }

    @Test
    void reportsAnUnknownLevelRatherThanInventingOne() {
        // Probing an init segment on its own yields this. The transcoder probes the variant playlist
        // precisely because of it; if that ever regresses, the codec string goes visibly wrong
        // rather than quietly plausible.
        given(processRunner.runCapturing(any(), any())).willReturn(INIT_SEGMENT_ONLY);

        assertThat(probe().probe(Path.of("720p_init.mp4")).videoLevel()).isEqualTo(-99);
    }

    @Test
    void failsClearlyWhenTheProbeProducesNothing() {
        given(processRunner.runCapturing(any(), any())).willReturn("");

        assertThatThrownBy(() -> probe().probe(Path.of("x.m3u8")))
                .isInstanceOf(TranscodingException.class)
                .hasMessageContaining("no output");
    }
}
