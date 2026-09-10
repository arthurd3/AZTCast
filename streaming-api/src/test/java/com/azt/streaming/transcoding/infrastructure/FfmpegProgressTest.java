package com.azt.streaming.transcoding.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FfmpegProgressTest {

    private final List<Integer> reported = new ArrayList<>();

    private FfmpegProgress progress(double durationSeconds) {
        return new FfmpegProgress(durationSeconds, reported::add);
    }

    @Test
    @DisplayName("turns out_time_us into whole percentages of the source duration")
    void reportsPercentages() {
        FfmpegProgress progress = progress(100);

        progress.accept("frame=240");
        progress.accept("out_time_us=25000000");
        progress.accept("out_time_us=50000000");
        progress.accept("progress=continue");

        assertThat(reported).containsExactly(25, 50);
    }

    @Test
    @DisplayName("only ever moves forward")
    void neverGoesBackwards() {
        // A source whose declared duration is shorter than its real one would otherwise walk the
        // bar backwards, and ffmpeg's own out_time can jitter around a keyframe.
        FfmpegProgress progress = progress(100);

        progress.accept("out_time_us=50000000");
        progress.accept("out_time_us=49000000");
        progress.accept("out_time_us=50000000");

        assertThat(reported).containsExactly(50);
    }

    @Test
    @DisplayName("reports whole points only, because each one costs a repository write")
    void collapsesSubPercentTicks() {
        FfmpegProgress progress = progress(1000);

        for (int second = 0; second < 20; second++) {
            progress.accept("out_time_us=" + (second * 1_000_000L));
        }

        // Twenty ticks over a 1000-second source is 2%. Three writes, not twenty — and the 0 is
        // the first tick, which the repository then filters out because the job already says 0.
        assertThat(reported).containsExactly(0, 1, 2);
    }

    @Test
    void finishesAtAHundredWhateverTheLastTimestampSaid() {
        FfmpegProgress progress = progress(100);

        progress.accept("out_time_us=99000000");
        progress.accept("progress=end");

        assertThat(reported).endsWith(100);
    }

    @Test
    void survivesTheThingsFfmpegActuallyPrints() {
        FfmpegProgress progress = progress(100);

        progress.accept(null);
        progress.accept("");
        // "N/A" is what the first tick of some inputs carries. Not an error, not news.
        progress.accept("out_time_us=N/A");
        progress.accept("[libopenh264 @ 0x55] Warning:bEnableFrameSkip = 0");
        progress.accept("out_time_us=10000000");

        assertThat(reported).containsExactly(10);
    }

    @Test
    void staysSilentWhenTheSourceDeclaredNoDuration() {
        // A percentage of an unknown total is an invented number, and an invented bar that stalls
        // is worse than no bar.
        FfmpegProgress progress = progress(0);

        progress.accept("out_time_us=10000000");

        assertThat(reported).isEmpty();
    }
}
