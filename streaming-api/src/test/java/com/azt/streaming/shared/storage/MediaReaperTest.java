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

class MediaReaperTest {

    private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");
    private static final Duration RETENTION = Duration.ofDays(7);

    @TempDir Path root;

    private Path downloads;
    private Path hls;

    @BeforeEach
    void setUp() throws IOException {
        downloads = Files.createDirectory(root.resolve("downloads"));
        hls = Files.createDirectory(root.resolve("hls"));
    }

    private MediaReaper reaper() {
        return new MediaReaper(
                PropertiesFixture.defaults().downloadsDir(downloads).hlsDir(hls).build(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** A video directory with one segment in it, aged by setting the file's mtime. */
    private Path video(Path parent, String id, Duration age) throws IOException {
        Path directory = Files.createDirectory(parent.resolve(id));
        Path segment = Files.writeString(directory.resolve("720p_000.m4s"), "bytes");
        FileTime when = FileTime.from(NOW.minus(age));
        Files.setLastModifiedTime(segment, when);
        Files.setLastModifiedTime(directory, when);
        return directory;
    }

    @Test
    void deletesMediaOlderThanTheRetentionWindow() throws IOException {
        Path old = video(hls, "old", RETENTION.plusDays(1));
        Path recent = video(hls, "recent", Duration.ofHours(1));

        reaper().reap();

        assertThat(old).doesNotExist();
        assertThat(recent).exists();
    }

    @Test
    void reapsDownloadsAndHlsAlike() throws IOException {
        Path oldDownload = video(downloads, "old", RETENTION.plusDays(1));
        Path oldHls = video(hls, "old", RETENTION.plusDays(1));

        reaper().reap();

        assertThat(oldDownload).doesNotExist();
        assertThat(oldHls).doesNotExist();
    }

    @Test
    @DisplayName("ages a directory by its newest file, not its own mtime")
    void doesNotReapADirectoryStillBeingWrittenInto() throws IOException {
        // A directory's own mtime only tracks its immediate entries, so a long transcode writing
        // segments would not refresh it. Judging by that alone, an encode running longer than the
        // retention window could have its output deleted underneath it.
        Path directory = video(hls, "in-progress", RETENTION.plusDays(1));
        Files.writeString(directory.resolve("720p_099.m4s"), "just written");

        reaper().reap();

        assertThat(directory).exists();
    }

    @Test
    void toleratesMissingRoots() {
        MediaReaper reaper = new MediaReaper(
                PropertiesFixture.defaults()
                        .downloadsDir(root.resolve("nope"))
                        .hlsDir(root.resolve("also-nope"))
                        .build(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        reaper.reap();
    }
}
