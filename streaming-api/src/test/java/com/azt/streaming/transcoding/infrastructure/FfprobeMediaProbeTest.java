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
import org.junit.jupiter.api.DisplayName;
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

    /**
     * Captured verbatim from a real 1080p H.264 + AAC-LC file, which is what most torrents are and
     * therefore the case the copy path is built for.
     */
    private static final String REAL_SOURCE_OUTPUT =
            """
            {
                "programs": [], "stream_groups": [],
                "streams": [
                    { "codec_name": "h264", "profile": "Main", "codec_type": "video",
                      "width": 1920, "height": 1080, "level": 40 },
                    { "codec_name": "aac", "profile": "LC", "codec_type": "audio" }
                ],
                "format": { "duration": "20.000000", "size": "15066342", "bit_rate": "6026536" }
            }
            """;

    /** Matroska, which routinely declares no bitrate at all. */
    private static final String MKV_WITHOUT_BITRATE =
            """
            {
                "programs": [], "stream_groups": [],
                "streams": [
                    { "codec_name": "h264", "profile": "High", "codec_type": "video",
                      "width": 1280, "height": 536, "level": 41 },
                    { "codec_name": "aac", "profile": "HE-AAC", "codec_type": "audio" }
                ],
                "format": { "duration": "100.0", "size": "12500000" }
            }
            """;

    @Test
    @DisplayName("reads the codec, dimensions and bitrate the ladder is planned from")
    void readsSourceCharacteristics() {
        given(processRunner.runCapturing(any(), any())).willReturn(REAL_SOURCE_OUTPUT);

        ProbedVideo probed = probe().probe(Path.of("movie.mp4"));

        assertThat(probed.videoCodec()).isEqualTo("h264");
        assertThat(probed.width()).isEqualTo(1920);
        assertThat(probed.height()).isEqualTo(1080);
        assertThat(probed.bitRateKbps()).isEqualTo(6026);
        assertThat(probed.audioCodec()).isEqualTo("aac");
        assertThat(probed.audioProfile()).isEqualTo("LC");
        assertThat(probed.videoIsCopyable()).isTrue();
        assertThat(probed.audioIsCopyable()).isTrue();
    }

    @Test
    @DisplayName("derives the bitrate from size and duration when the container declares none")
    void derivesBitrateForMatroska() {
        // Not an error case: Matroska is the container most torrents arrive in, and a muxer that
        // omits bit_rate is the normal one. 12500000 bytes over 100s is 1000 kbps.
        given(processRunner.runCapturing(any(), any())).willReturn(MKV_WITHOUT_BITRATE);

        ProbedVideo probed = probe().probe(Path.of("movie.mkv"));

        assertThat(probed.bitRateKbps()).isEqualTo(1000);
        assertThat(probed.videoIsCopyable()).isTrue();
        // HE-AAC, so the video is copied but the audio is not: mp4a.40.2 is a constant in the
        // CODECS string and would be a lie about this track.
        assertThat(probed.audioIsCopyable()).isFalse();
    }

    @Test
    @DisplayName("a probed rung reports no dimensions, so nothing mistakes it for a source")
    void anOutputProbeIsNotASource() {
        given(processRunner.runCapturing(any(), any())).willReturn(REAL_720P_OUTPUT);

        ProbedVideo probed = probe().probe(Path.of("720p.m3u8"));

        assertThat(probed.height()).isZero();
        assertThat(probed.videoIsCopyable()).isFalse();
    }

    @Test
    void asksFfprobeForEverythingTheLadderNeeds() {
        assertThat(probe().command(Path.of("in.mkv")))
                .contains("stream=codec_type,codec_name,profile,level,width,height:format=bit_rate,duration,size");
    }
}
