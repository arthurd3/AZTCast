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
 * Deletes media that nothing refers to any more.
 *
 * <p>The runbook has said "nothing prunes automatically; a long-running instance will fill its disk"
 * since it was written. That was survivable while job state was ephemeral, because nothing claimed
 * the media still existed. It is not survivable alongside a job TTL: media kept forever while its
 * job record expires leaves directories no one can name, and a job that outlived its media would
 * report READY for a video that is gone. Retention and the job TTL are two halves of one number.
 *
 * <p>No distributed lock. Deletion here is idempotent — two instances reaping the same directory is
 * not a race worth coordinating, and the guard would be more machinery than the problem.
 *
 * <h2>What "kept" does to that</h2>
 *
 * <p>A video marked with {@link VideoCatalog#KEEP_FILE} is never reaped. That deliberately breaks
 * half of the retention/TTL pairing above, and only the half that is safe to break: the job record
 * still expires on schedule, so a kept video ends up listed with no job behind it — which is
 * already the ordinary state of every video after a restart, because the catalogue reads the disk
 * and not job state. The dangerous direction, a job reporting READY for media that is gone, stays
 * impossible.
 *
 * <p>The marker exempts the HLS ladder only. The raw torrent under {@code downloads} is reaped on
 * schedule either way: it is a second full copy of the video, it is not what anyone watches, and
 * nothing seeds it once the download stops.
 */
@Slf4j
@Component
public class MediaReaper {

    private final Path downloadsRoot;
    private final Path hlsRoot;
    private final Duration retention;
    private final Clock clock;

    public MediaReaper(StreamingProperties properties, Clock clock) {
        this.downloadsRoot = properties.storage().downloadsDir();
        this.hlsRoot = properties.storage().hlsDir();
        this.retention = properties.storage().retention();
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
        int removed = reapRoot(hlsRoot, true) + reapRoot(downloadsRoot, false);
        if (removed > 0) {
            log.info("Reaped {} media director(ies) older than {}", removed, retention);
        }
    }

    private int reapRoot(Path root, boolean honourKeepMarkers) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        Instant cutoff = clock.instant().minus(retention);
        List<Path> expired;
        try (Stream<Path> entries = Files.list(root)) {
            expired = entries.filter(Files::isDirectory)
                    .filter(directory -> !(honourKeepMarkers && isKept(directory)))
                    .filter(directory -> lastModified(directory).isBefore(cutoff))
                    .toList();
        } catch (IOException e) {
            log.warn("Could not list {} for reaping", root, e);
            return 0;
        }
        int removed = 0;
        for (Path directory : expired) {
            if (deleteRecursively(directory)) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * The newest mtime anywhere inside, not the directory's own.
     *
     * <p>A directory's mtime only tracks changes to its immediate entries, so a long transcode
     * writing segments into it would not refresh it — and an ingestion that ran longer than the
     * retention window could have its own output deleted underneath it mid-encode.
     */
    private Instant lastModified(Path directory) {
        try (Stream<Path> tree = Files.walk(directory)) {
            return tree.map(MediaReaper::mtime).max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
        } catch (IOException | UncheckedIOException e) {
            log.warn("Could not stat {}; leaving it alone", directory, e);
            // Never reap something we failed to read. Deleting on uncertainty is the wrong default
            // when the thing being deleted is the product.
            return Instant.MAX;
        }
    }

    /**
     * Whether someone asked for this video to be kept.
     *
     * <p>Checked before the mtime rather than after, so a kept directory is never even stat-walked.
     */
    private static boolean isKept(Path directory) {
        return Files.exists(directory.resolve(VideoCatalog.KEEP_FILE));
    }

    private static Instant mtime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean deleteRecursively(Path directory) {
        try (Stream<Path> tree = Files.walk(directory)) {
            // Reverse order so children are removed before their parents.
            for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
            return true;
        } catch (IOException e) {
            log.warn("Could not delete {}", directory, e);
            return false;
        }
    }
}
