package com.azt.streaming.transcoding.infrastructure;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Reads {@code ffmpeg -progress} output and reports how far along the encode is.
 *
 * <p>Transcoding used to report a hardcoded 100%. {@code StreamJob.transcoding()} set the job's
 * progress to full the moment the download finished, so the bar sat at the end for the entire encode
 * — which on a feature-length source is the longest and least visible part of the pipeline.
 *
 * <p>{@code -progress} emits {@code key=value} lines every second and a final {@code progress=end}.
 * They cannot be confused with ffmpeg's own log output, which never has that shape, so the merged
 * stream is safe to read as-is.
 *
 * <p>Reports only whole percentage points, and only increases. Each one costs a repository write, so
 * a per-second float would spend a Redis round trip to move a bar by nothing; and a source whose
 * declared duration is shorter than its real one would otherwise walk the bar backwards.
 */
class FfmpegProgress implements Consumer<String> {

    private static final String OUT_TIME = "out_time_us=";
    private static final String COMPLETED = "progress=end";
    private static final int MICROS_PER_SECOND = 1_000_000;

    private final double durationSeconds;
    private final IntConsumer onPercent;
    private int reported = -1;

    FfmpegProgress(double durationSeconds, IntConsumer onPercent) {
        this.durationSeconds = durationSeconds;
        this.onPercent = onPercent;
    }

    @Override
    public void accept(String line) {
        if (line == null) {
            return;
        }
        String trimmed = line.strip();
        if (trimmed.equals(COMPLETED)) {
            report(100);
            return;
        }
        if (!trimmed.startsWith(OUT_TIME) || durationSeconds <= 0) {
            return;
        }
        long micros;
        try {
            micros = Long.parseLong(trimmed.substring(OUT_TIME.length()));
        } catch (NumberFormatException e) {
            // ffmpeg writes "N/A" here for the first tick of some inputs. Not an error, not news.
            return;
        }
        double seconds = (double) micros / MICROS_PER_SECOND;
        report((int) Math.min(100, Math.max(0, Math.round(seconds / durationSeconds * 100))));
    }

    private void report(int percent) {
        if (percent > reported) {
            reported = percent;
            onPercent.accept(percent);
        }
    }
}
