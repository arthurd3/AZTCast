package com.azt.streaming.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.azt.streaming.support.PropertiesFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoCatalogTest {

    private static final String VIDEO_ID = "2724a02c-f275-49d2-8389-e76bfeacd4c6";

    @TempDir Path root;
    private Path hlsRoot;
    private VideoCatalog catalog;

    @BeforeEach
    void setUp() {
        hlsRoot = root.resolve("hls");
        FileSystemMediaStorage storage =
                new FileSystemMediaStorage(
                        PropertiesFixture.defaults()
                                .hlsDir(hlsRoot)
                                .downloadsDir(root.resolve("downloads"))
                                .build());
        storage.createRoots();
        catalog = new VideoCatalog(storage, new ObjectMapper());
    }

    @Test
    @DisplayName("a video with no job record is still listed — the disk is the store, not Redis")
    void listsMediaThatHasNoJobRecord() throws IOException {
        // The case this class exists for: Redis runs without persistence, so a restart drops every
        // job while the HLS output survives. A catalogue built on job state would show nothing here.
        ready(VIDEO_ID);

        assertThat(catalog.list()).singleElement().satisfies(entry -> {
            assertThat(entry.videoId()).isEqualTo(VIDEO_ID);
            assertThat(entry.title()).isNull();
        });
    }

    @Test
    void ignoresADirectoryWithoutAMasterPlaylist() throws IOException {
        Files.createDirectories(hlsRoot.resolve("half-done"));
        Files.writeString(hlsRoot.resolve("half-done").resolve("720p_000.m4s"), "x");

        assertThat(catalog.list()).isEmpty();
    }

    @Test
    void ordersNewestFirst() throws IOException {
        ready("older");
        ready("newer");
        touch(hlsRoot.resolve("older"), Instant.parse("2026-09-01T10:00:00Z"));
        touch(hlsRoot.resolve("newer"), Instant.parse("2026-09-08T10:00:00Z"));

        assertThat(catalog.list())
                .extracting(CatalogEntry::videoId)
                .containsExactly("newer", "older");
    }

    @Test
    void readsBackTheTitleItRecorded() throws IOException {
        ready(VIDEO_ID);

        catalog.record(VIDEO_ID, "Big.Buck.Bunny.2008.1080p.mkv");

        assertThat(catalog.list())
                .singleElement()
                .extracting(CatalogEntry::title)
                .isEqualTo("Big.Buck.Bunny.2008.1080p.mkv");
    }

    @Test
    @DisplayName("a corrupt sidecar costs the title, not the video")
    void survivesAnUnreadableSidecar() throws IOException {
        ready(VIDEO_ID);
        // A crash mid-write leaves exactly this: a truncated file that is not JSON.
        Files.writeString(hlsRoot.resolve(VIDEO_ID).resolve(VideoCatalog.METADATA_FILE), "{\"title\":");

        assertThat(catalog.list()).singleElement().satisfies(entry -> {
            assertThat(entry.videoId()).isEqualTo(VIDEO_ID);
            assertThat(entry.title()).isNull();
        });
    }

    @Test
    void treatsABlankTitleAsNoTitle() throws IOException {
        ready(VIDEO_ID);
        Files.writeString(hlsRoot.resolve(VIDEO_ID).resolve(VideoCatalog.METADATA_FILE), "{\"title\":\"  \"}");

        assertThat(catalog.list()).singleElement().extracting(CatalogEntry::title).isNull();
    }

    @Test
    void advertisesRenditionsHighestFirstAndIgnoresStrayPlaylists() throws IOException {
        ready(VIDEO_ID);
        Path directory = hlsRoot.resolve(VIDEO_ID);
        for (String rendition : new String[] {"240p", "1080p", "720p"}) {
            Files.writeString(directory.resolve(rendition + ".m3u8"), "#EXTM3U\n");
        }
        // Media directories in the wild have picked up hand-made playlists. Offering "cur" as a
        // quality would be a lie about what the encoder wrote.
        Files.writeString(directory.resolve("cur.m3u8"), "#EXTM3U\n");
        Files.writeString(directory.resolve("long.m3u8"), "#EXTM3U\n");
        Files.write(directory.resolve(MediaStorage.POSTER), new byte[] {(byte) 0xFF, (byte) 0xD8});

        assertThat(catalog.list())
                .singleElement()
                .extracting(CatalogEntry::qualities)
                .isEqualTo(java.util.List.of("1080p", "720p", "240p"));
    }

    @Test
    void sumsWhatTheLadderOccupiesOnDisk() throws IOException {
        ready(VIDEO_ID); // master.m3u8 is 8 bytes
        Files.write(hlsRoot.resolve(VIDEO_ID).resolve("720p_000.m4s"), new byte[1024]);

        assertThat(catalog.list()).singleElement().extracting(CatalogEntry::sizeBytes).isEqualTo(1024L + 8L);
    }

    @Test
    void reportsAPosterOnlyWhenOneWasExtracted() throws IOException {
        ready(VIDEO_ID);
        ready("no-poster");
        Files.write(hlsRoot.resolve(VIDEO_ID).resolve(MediaStorage.POSTER), new byte[] {(byte) 0xFF, (byte) 0xD8});

        assertThat(catalog.list())
                .filteredOn(entry -> entry.videoId().equals(VIDEO_ID))
                .singleElement()
                .extracting(CatalogEntry::hasPoster)
                .isEqualTo(true);
        assertThat(catalog.list())
                .filteredOn(entry -> entry.videoId().equals("no-poster"))
                .singleElement()
                .extracting(CatalogEntry::hasPoster)
                .isEqualTo(false);
    }

    @Test
    void recordingATitleNeverThrowsWhenTheWriteCannotHappen() {
        // A title is a nicety. An ingestion that already downloaded and transcoded a torrent must
        // not be failed because a small file could not be written.
        catalog.record("../escape", "anything");

        assertThat(catalog.list()).isEmpty();
    }

    /** A finished video: the master playlist is what makes it watchable. */
    private void ready(String videoId) throws IOException {
        Path directory = Files.createDirectories(hlsRoot.resolve(videoId));
        Files.writeString(directory.resolve(MediaStorage.MASTER_PLAYLIST), "#EXTM3U\n");
    }

    private static void touch(Path directory, Instant when) throws IOException {
        Files.setLastModifiedTime(
                directory.resolve(MediaStorage.MASTER_PLAYLIST), FileTime.from(when));
    }
}
