package com.azt.streaming.transcoding.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.azt.streaming.support.PropertiesFixture;
import com.azt.streaming.transcoding.domain.ProbedAudio;
import com.azt.streaming.transcoding.domain.ProbedSource;
import com.azt.streaming.transcoding.domain.ProbedSubtitle;
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
        assertThat(probed.codecs()).isEqualTo("avc1.4d001f");
    }

    @Test
    void distinguishesRungsThatTheOldHardcodedStringConflated() {
        given(processRunner.runCapturing(any(), any())).willReturn(REAL_240P_OUTPUT);

        // The ladder asks every rung for Main; 240p still comes out at level 2.1 because the level
        // follows resolution and bitrate. This is the measurement the master playlist now carries.
        assertThat(probe().probe(Path.of("240p.m3u8")).codecs()).isEqualTo("avc1.4d0015");
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
     * Captured verbatim from the file that produced the defect this was all written for: a
     * 1080p H.264 AMZN WEB-DL with a single 5.1 E-AC-3 track and four subtitle tracks. Every
     * field that ends up mattering — the channel count, the frame rate, the language tags, the
     * SDH disposition — is present here and was invisible to the probe this replaced.
     */
    private static final String REAL_EAC3_MKV =
            """
            {
                "programs": [], "stream_groups": [],
                "streams": [
                    { "index": 0, "codec_name": "h264", "profile": "High", "codec_type": "video",
                      "width": 1920, "height": 1080, "level": 40, "r_frame_rate": "24000/1001",
                      "disposition": { "default": 1, "forced": 0, "hearing_impaired": 0 },
                      "tags": { "language": "eng" } },
                    { "index": 1, "codec_name": "eac3", "codec_type": "audio",
                      "sample_rate": "48000", "channels": 6, "r_frame_rate": "0/0",
                      "disposition": { "default": 1, "forced": 0, "hearing_impaired": 0 },
                      "tags": { "language": "eng" } },
                    { "index": 2, "codec_name": "subrip", "codec_type": "subtitle", "r_frame_rate": "0/0",
                      "disposition": { "default": 0, "forced": 0, "hearing_impaired": 0 },
                      "tags": { "language": "eng" } },
                    { "index": 3, "codec_name": "subrip", "codec_type": "subtitle", "r_frame_rate": "0/0",
                      "disposition": { "default": 0, "forced": 0, "hearing_impaired": 1 },
                      "tags": { "language": "eng", "title": "SDH" } },
                    { "index": 4, "codec_name": "subrip", "codec_type": "subtitle", "r_frame_rate": "0/0",
                      "disposition": { "default": 0, "forced": 0, "hearing_impaired": 0 },
                      "tags": { "language": "spa", "title": "Latin American" } },
                    { "index": 5, "codec_name": "subrip", "codec_type": "subtitle", "r_frame_rate": "0/0",
                      "disposition": { "default": 0, "forced": 0, "hearing_impaired": 0 },
                      "tags": { "language": "por", "title": "Brazilian" } }
                ],
                "format": { "duration": "1357.815000", "size": "756885676", "bit_rate": "4459433" }
            }
            """;

    /** Matroska, which routinely declares no bitrate at all. */
    private static final String MKV_WITHOUT_BITRATE =
            """
            {
                "programs": [], "stream_groups": [],
                "streams": [
                    { "index": 0, "codec_name": "h264", "profile": "High", "codec_type": "video",
                      "width": 1280, "height": 536, "level": 41, "r_frame_rate": "25/1" },
                    { "index": 1, "codec_name": "aac", "profile": "HE-AAC", "codec_type": "audio",
                      "channels": 2, "sample_rate": "44100" }
                ],
                "format": { "duration": "100.0", "size": "12500000" }
            }
            """;

    /** A release whose feature audio is not the first track. */
    private static final String COMMENTARY_FIRST =
            """
            {
                "programs": [], "stream_groups": [],
                "streams": [
                    { "index": 0, "codec_name": "h264", "profile": "Main", "codec_type": "video",
                      "width": 640, "height": 360, "level": 30, "r_frame_rate": "24/1" },
                    { "index": 1, "codec_name": "aac", "profile": "LC", "codec_type": "audio",
                      "channels": 2, "sample_rate": "48000", "tags": { "title": "Commentary" },
                      "disposition": { "default": 0 } },
                    { "index": 2, "codec_name": "aac", "profile": "LC", "codec_type": "audio",
                      "channels": 2, "sample_rate": "48000", "disposition": { "default": 1 } }
                ],
                "format": { "duration": "60.0", "size": "6000000" }
            }
            """;

    @Test
    @DisplayName("reads every stream, tag and disposition the plan is built from")
    void readsSourceCharacteristics() {
        given(processRunner.runCapturing(any(), any())).willReturn(REAL_EAC3_MKV);

        ProbedSource source = probe().probeSource(Path.of("episode.mkv"));

        assertThat(source.videoCodec()).isEqualTo("h264");
        assertThat(source.width()).isEqualTo(1920);
        assertThat(source.height()).isEqualTo(1080);
        assertThat(source.bitRateKbps()).isEqualTo(4459);
        assertThat(source.durationSeconds()).isEqualTo(1357.815);
        assertThat(source.videoIsCopyable()).isTrue();
        // 24000/1001, the rate every 23.976 release actually carries.
        assertThat(source.frameRate()).isCloseTo(23.976, org.assertj.core.data.Offset.offset(0.001));
        assertThat(source.framesPerSegment(4)).isEqualTo(96);

        ProbedAudio audio = source.primaryAudio().orElseThrow();
        assertThat(audio.codec()).isEqualTo("eac3");
        assertThat(audio.channels()).isEqualTo(6);
        assertThat(audio.sampleRate()).isEqualTo(48000);
        assertThat(audio.language()).isEqualTo("eng");
        // fMP4 carries E-AC-3, which is the whole reason a build with no decoder for it can still
        // publish this file.
        assertThat(audio.isPackageable()).isTrue();
        assertThat(audio.isPlainAac()).isFalse();
        assertThat(audio.codecsWhenCopied()).isEqualTo("ec-3");

        assertThat(source.subtitleTracks()).hasSize(4);
        assertThat(source.textSubtitles()).hasSize(4);
        assertThat(source.subtitleTracks().stream().map(ProbedSubtitle::label))
                .containsExactly("English", "English (SDH)", "Spanish (Latin American)", "Portuguese (Brazilian)");
        assertThat(source.subtitleTracks().get(1).hearingImpaired()).isTrue();
        // The index is the ordinal among subtitle streams, not the container index: `-map 0:s:1`
        // is the SDH track even though it sits at container index 3.
        assertThat(source.subtitleTracks().get(1).index()).isEqualTo(1);
    }

    @Test
    @DisplayName("derives the bitrate from size and duration when the container declares none")
    void derivesBitrateForMatroska() {
        // Not an error case: Matroska is the container most torrents arrive in, and a muxer that
        // omits bit_rate is the normal one. 12500000 bytes over 100s is 1000 kbps.
        given(processRunner.runCapturing(any(), any())).willReturn(MKV_WITHOUT_BITRATE);

        ProbedSource source = probe().probeSource(Path.of("movie.mkv"));

        assertThat(source.bitRateKbps()).isEqualTo(1000);
        assertThat(source.videoIsCopyable()).isTrue();
        // HE-AAC, so mp4a.40.2 would be a lie about this track and it cannot simply be copied.
        assertThat(source.primaryAudio().orElseThrow().isPlainAac()).isFalse();
    }

    @Test
    @DisplayName("prefers the track the container marks default over whichever came first")
    void picksTheDefaultAudioTrack() {
        // Taking a:0 unconditionally serves the commentary as the feature audio, and nothing about
        // the output would say so.
        given(processRunner.runCapturing(any(), any())).willReturn(COMMENTARY_FIRST);

        ProbedSource source = probe().probeSource(Path.of("movie.mkv"));

        assertThat(source.audioTracks()).hasSize(2);
        assertThat(source.primaryAudio().orElseThrow().index()).isEqualTo(1);
        assertThat(source.audioTracks().getFirst().title()).isEqualTo("Commentary");
    }

    @Test
    @DisplayName("a rung probe asks for three fields; a source probe asks for everything")
    void asksFfprobeForExactlyWhatEachQuestionNeeds() {
        assertThat(probe().command(Path.of("in.m3u8"))).contains("stream=codec_type,profile,level");
        assertThat(probe().sourceCommand(Path.of("in.mkv")))
                .anyMatch(argument -> argument.contains("channels,sample_rate,r_frame_rate")
                        && argument.contains("stream_tags=language,title")
                        && argument.contains("stream_disposition=default,forced,hearing_impaired")
                        && argument.contains("format=bit_rate,duration,size"));
    }
}
