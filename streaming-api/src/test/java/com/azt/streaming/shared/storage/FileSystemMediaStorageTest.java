package com.azt.streaming.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.azt.streaming.support.PropertiesFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileSystemMediaStorageTest {

    private static final String VIDEO_ID = "2724a02c-f275-49d2-8389-e76bfeacd4c6";

    @TempDir Path root;
    private Path hlsRoot;
    private FileSystemMediaStorage storage;

    @BeforeEach
    void setUp() {
        hlsRoot = root.resolve("hls");
        storage =
                new FileSystemMediaStorage(
                        PropertiesFixture.defaults()
                                .hlsDir(hlsRoot)
                                .downloadsDir(root.resolve("downloads"))
                                .build());
        storage.createRoots();
    }

    @Test
    void createsBothRootsIncludingMissingParents() {
        // The old code called File.mkdir() — single level — against a two-level path and ignored
        // the result, so it silently did nothing whenever the parent was absent.
        assertThat(hlsRoot).isDirectory();
        assertThat(root.resolve("downloads")).isDirectory();
    }

    @Test
    void resolvesAnAssetThatExists() throws IOException {
        Path master = writeAsset("master.m3u8");

        assertThat(storage.resolveHlsAsset(VIDEO_ID, "master.m3u8")).hasValue(master);
    }

    @Test
    void returnsEmptyWhenTheAssetIsMissing() {
        assertThat(storage.resolveHlsAsset(VIDEO_ID, "master.m3u8")).isEmpty();
    }

    @Test
    void returnsEmptyForADirectoryRatherThanAFile() throws IOException {
        Files.createDirectories(hlsRoot.resolve(VIDEO_ID).resolve("segments"));

        assertThat(storage.resolveHlsAsset(VIDEO_ID, "segments")).isEmpty();
    }

    @ParameterizedTest(name = "file=\"{0}\"")
    @ValueSource(
            strings = {
                "../../../etc/passwd",
                "..",
                "../master.m3u8",
                "a/../../../../etc/passwd",
                "/etc/passwd",
                "./../../secret"
            })
    void refusesToEscapeTheMediaRootViaTheFileName(String fileName) throws IOException {
        // A real file outside the root, so a successful escape would return something.
        Files.writeString(root.resolve("secret"), "should never be served");

        assertThat(storage.resolveHlsAsset(VIDEO_ID, fileName)).isEmpty();
    }

    @ParameterizedTest(name = "videoId=\"{0}\"")
    @ValueSource(strings = {"../..", "../../..", "/etc", "..%2f..", "a/../.."})
    void refusesToEscapeTheMediaRootViaTheVideoId(String videoId) throws IOException {
        Files.writeString(root.resolve("secret"), "should never be served");

        assertThat(storage.resolveHlsAsset(videoId, "secret")).isEmpty();
    }

    @Test
    void hlsDirectoryForCreatesTheVideoFolderInsideTheRoot() {
        Path directory = storage.hlsDirectoryFor(VIDEO_ID);

        assertThat(directory).isDirectory().startsWith(hlsRoot).hasFileName(VIDEO_ID);
    }

    @Test
    void downloadDirectoryForStaysInsideTheDownloadsRoot() {
        assertThat(storage.downloadDirectoryFor(VIDEO_ID))
                .startsWith(root.resolve("downloads"))
                .hasFileName(VIDEO_ID);
    }

    @Test
    void listsOnlyVideosWhoseLadderIsComplete() throws IOException {
        writeAsset("master.m3u8");
        // Mid-transcode: segments are landing but the master playlist is written last, so this one
        // is not watchable yet and must not be offered.
        Files.createDirectories(hlsRoot.resolve("half-done"));
        Files.writeString(hlsRoot.resolve("half-done").resolve("720p_000.m4s"), "x");

        assertThat(storage.listReadyVideoDirectories())
                .singleElement()
                .satisfies(directory -> assertThat(directory).hasFileName(VIDEO_ID));
    }

    @Test
    void listsNothingWhenTheRootIsMissing() throws IOException {
        Files.delete(hlsRoot);

        assertThat(storage.listReadyVideoDirectories()).isEmpty();
    }

    private Path writeAsset(String fileName) throws IOException {
        Path directory = Files.createDirectories(hlsRoot.resolve(VIDEO_ID));
        return Files.writeString(directory.resolve(fileName), "#EXTM3U\n");
    }
}
