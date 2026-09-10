package com.azt.streaming.transcoding.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;

import com.azt.streaming.support.PropertiesFixture;
import com.azt.streaming.transcoding.domain.SubtitlePlan;
import com.azt.streaming.transcoding.domain.TranscodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SubtitlePublisherTest {

    private static final SubtitlePlan ENGLISH = new SubtitlePlan(0, "sub_en-0", "English", "en", false, false);
    private static final SubtitlePlan BRAZILIAN =
            new SubtitlePlan(1, "sub_pt-3", "Portuguese (Brazilian)", "pt", false, false);

    @Mock private ProcessRunner processRunner;

    private SubtitlePublisher publisher() {
        return new SubtitlePublisher(
                new FfmpegCommandBuilder(PropertiesFixture.defaults().build()), processRunner);
    }

    /** Stands in for ffmpeg: writes whatever the command's last argument names. */
    private void writingCues(String contents) {
        willAnswer(invocation -> {
                    List<String> command = invocation.getArgument(0);
                    Files.writeString(Path.of(command.getLast()), contents);
                    return null;
                })
                .given(processRunner)
                .run(any(), any());
    }

    @Test
    @DisplayName("writes a playlist beside each track it managed to extract")
    void publishesEachTrack(@TempDir Path directory) throws Exception {
        writingCues("WEBVTT\n\n00:00:01.000 --> 00:00:04.000\nA caption.\n");

        List<SubtitlePlan> published = publisher().publish(
                Path.of("/in/movie.mkv"), directory, List.of(ENGLISH, BRAZILIAN), 1357.815);

        assertThat(published).containsExactly(ENGLISH, BRAZILIAN);
        assertThat(directory.resolve("sub_en-0.vtt")).exists();
        assertThat(directory.resolve("sub_en-0.m3u8")).exists();
        assertThat(directory.resolve("sub_pt-3.m3u8")).exists();
    }

    @Test
    @DisplayName("a track that fails to extract is never advertised")
    void leavesOutWhatItCouldNotExtract(@TempDir Path directory) {
        willThrow(new TranscodingException("ffmpeg exited with code 1")).given(processRunner).run(any(), any());

        List<SubtitlePlan> published =
                publisher().publish(Path.of("/in/movie.mkv"), directory, List.of(ENGLISH), 100);

        // Advertised-but-missing is worse than absent: the picker offers a track that 404s.
        assertThat(published).isEmpty();
        assertThat(directory.resolve("sub_en-0.m3u8")).doesNotExist();
    }

    @Test
    @DisplayName("a track of nothing but formatting is dropped rather than offered empty")
    void dropsEmptyTracks(@TempDir Path directory) {
        writingCues("");

        assertThat(publisher().publish(Path.of("/in/movie.mkv"), directory, List.of(ENGLISH), 100))
                .isEmpty();
        assertThat(directory.resolve("sub_en-0.vtt")).doesNotExist();
    }

    @Test
    @DisplayName("the playlist's EXTINF is parseable under any locale")
    void writesADecimalPointWhateverTheDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            // pt_BR formats 3.999 as "3,999", and a comma in an EXTINF is a playlist no player
            // will parse. This project is Brazilian; the default locale here is exactly that one.
            Locale.setDefault(Locale.forLanguageTag("pt-BR"));
            String playlist = publisher().renderPlaylist(ENGLISH, 1357.815);

            assertThat(playlist).contains("#EXTINF:1357.815,").doesNotContain(",815");
            assertThat(playlist).contains("#EXT-X-TARGETDURATION:1358").contains("sub_en-0.vtt");
            assertThat(playlist).endsWith("#EXT-X-ENDLIST\n");
        } finally {
            Locale.setDefault(original);
        }
    }
}
