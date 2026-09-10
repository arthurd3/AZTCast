package com.azt.streaming.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.azt.streaming.support.PropertiesFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadReaperTest {

    private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");
    private static final Duration RETENTION = Duration.ofHours(24);

    @TempDir Path root;

    private Path downloads;
    private Path hls;

    @BeforeEach
    void setUp() throws IOException {
        downloads = Files.createDirectory(root.resolve("downloads"));
        hls = Files.createDirectory(root.resolve("hls"));
    }

    private DownloadReaper reaper() {
        return new DownloadReaper(
                PropertiesFixture.defaults()
                        .downloadsDir(downloads)
                        .hlsDir(hls)
                        .downloadRetention(RETENTION)
                        .build(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** A video directory with one file in it, aged by setting the file's mtime. */
    private Path video(Path parent, String id, Duration age) throws IOException {
        Path directory = Files.createDirectory(parent.resolve(id));
        Path segment = Files.writeString(directory.resolve("720p_000.m4s"), "bytes");
        FileTime when = FileTime.from(NOW.minus(age));
        Files.setLastModifiedTime(segment, when);
        Files.setLastModifiedTime(directory, when);
        return directory;
    }

    @Test
    void deletesDownloadsOlderThanTheWindow() throws IOException {
        Path old = video(downloads, "old", RETENTION.plusHours(1));
        Path recent = video(downloads, "recent", Duration.ofMinutes(10));

        reaper().reap();

        assertThat(old).doesNotExist();
        assertThat(recent).exists();
    }

    @Test
    @DisplayName("never touches the HLS root, however old what is in it")
    void doesNotReapTheLadder() throws IOException {
        // The rule this replaces deleted both roots on one seven-day clock, which made the library
        // a cache. A finished ladder is the product and now leaves only when someone deletes it.
        Path ancient = video(hls, "ancient", RETENTION.multipliedBy(365));
        Path download = video(downloads, "ancient", RETENTION.plusHours(1));

        reaper().reap();

        assertThat(ancient).exists();
        assertThat(download).doesNotExist();
    }

    @Test
    @DisplayName("ages a directory by its newest file, not its own mtime")
    void doesNotReapADirectoryStillBeingWrittenInto() throws IOException {
        // A directory's own mtime only tracks its immediate entries, so a torrent still arriving
        // would not refresh it. Judging by that alone, a download slower than the window could be
        // deleted out from under the client fetching it.
        Path directory = video(downloads, "in-progress", RETENTION.plusHours(1));
        Files.writeString(directory.resolve("720p_099.m4s"), "just written");

        reaper().reap();

        assertThat(directory).exists();
    }

    @Test
    @DisplayName("the keep marker means nothing here — it never exempted a download")
    void theMarkerDoesNotExemptTheDownload() throws IOException {
        // Unchanged by the move to a downloads-only sweep, and worth pinning for that reason: the
        // marker guards the ladder from a deliberate delete, not the torrent from this.
        Path download = video(downloads, "kept", RETENTION.plusHours(1));
        Path marker = Files.writeString(download.resolve(VideoCatalog.KEEP_FILE), "{}");
        FileTime when = FileTime.from(NOW.minus(RETENTION.plusHours(1)));
        Files.setLastModifiedTime(marker, when);
        Files.setLastModifiedTime(download, when);

        reaper().reap();

        assertThat(download).doesNotExist();
    }

    @Test
    void toleratesAMissingRoot() {
        DownloadReaper reaper = new DownloadReaper(
                PropertiesFixture.defaults()
                        .downloadsDir(root.resolve("nope"))
                        .hlsDir(root.resolve("also-nope"))
                        .build(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        reaper.reap();
    }
}
