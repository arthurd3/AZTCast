package com.azt.streaming.shared.storage;

import com.azt.streaming.shared.config.StreamingProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes raw torrent downloads that nothing is going to use again.
 *
 * <p>This used to sweep the HLS ladder too, on a seven-day clock, and that was the only thing in the
 * application that deleted media. It was the wrong rule for where this runs. The disk belongs to the
 * person at the keyboard and the ladder is what they asked for; a library that quietly empties itself
 * after a week is a cache wearing a library's name. The ladder is now permanent and deletion is
 * something a person does — see {@code DELETE /api/v1/videos/{videoId}} and ADR-0030.
 *
 * <p>What is left is the half that was always regenerable. The raw torrent under {@code downloads} is
 * a second full copy of a video nobody watches, nothing seeds it once the download stops, and repair
 * does not need it — that path goes back to the magnet recorded in {@code meta.json}. The ordinary
 * way it goes is {@link MediaStorage#discardDownload(String)}, called the moment a transcode is
 * verified, which frees the space in minutes rather than days.
 *
 * <h2>Why this still exists at all</h2>
 *
 * <p>As a backstop for the copies that never reach that call: a download that failed, an encode that
 * crashed, a process killed between the two. Those leave bytes under {@code downloads} that no code
 * path will ever come back for, and only a sweep finds them.
 *
 * <p>No distributed lock. Deletion here is idempotent — two instances reaping the same directory is
 * not a race worth coordinating, and the guard would be more machinery than the problem.
 */
@Slf4j
@Component
public class DownloadReaper {

    private final Path downloadsRoot;
    private final Duration retention;
    private final Clock clock;

    public DownloadReaper(StreamingProperties properties, Clock clock) {
        this.downloadsRoot = properties.storage().downloadsDir();
        this.retention = properties.storage().downloadRetention();
        this.clock = clock;
    }

    /**
     * Hourly, with an initial delay so it never competes with startup.
     *
     * <p>Fixed delay rather than a cron expression: the useful property is "an hour of not reaping
     * has passed", not "it is the top of the hour", and a fixed delay cannot pile up runs if one
     * takes longer than the interval.
     */
    @Scheduled(initialDelay = 5, fixedDelay = 60, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    public void reap() {
        if (!Files.isDirectory(downloadsRoot)) {
            return;
        }
        Instant cutoff = clock.instant().minus(retention);
        List<Path> expired;
        try (Stream<Path> entries = Files.list(downloadsRoot)) {
            expired = entries.filter(Files::isDirectory)
                    .filter(directory -> lastModified(directory).isBefore(cutoff))
                    .toList();
        } catch (IOException e) {
            log.warn("Could not list {} for reaping", downloadsRoot, e);
            return;
        }
        int removed = 0;
        for (Path directory : expired) {
            if (MediaDirectories.deleteRecursively(directory)) {
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Reaped {} abandoned download(s) older than {}", removed, retention);
        }
    }

    /**
     * The newest mtime anywhere inside, not the directory's own.
     *
     * <p>A directory's mtime only tracks changes to its immediate entries, so a torrent still
     * arriving would not refresh it — and a download slower than the window could be deleted out from
     * under the client fetching it.
     */
    private Instant lastModified(Path directory) {
        try (Stream<Path> tree = Files.walk(directory)) {
            return tree.map(DownloadReaper::mtime).max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
        } catch (IOException | UncheckedIOException e) {
            log.warn("Could not stat {}; leaving it alone", directory, e);
            // Never reap something we failed to read. Deleting on uncertainty is the wrong default
            // even when the thing being deleted is only a copy.
            return Instant.MAX;
        }
    }

    private static Instant mtime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
