package com.azt.streaming.acquisition.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.azt.streaming.shared.config.StreamingProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoFileLocatorTest {

    private final VideoFileLocator locator = new VideoFileLocator(propertiesWith("mp4", "mkv"));

    @Test
    void findsAVideoNestedInsideAFolder(@TempDir Path root) throws IOException {
        // The shape almost every real torrent has, and the one the old non-recursive
        // File.listFiles() implementation always failed on.
        Path nested = Files.createDirectories(root.resolve("Some.Release.1080p/Subs"));
        write(nested.getParent().resolve("movie.mp4"), 5_000);
        write(nested.resolve("english.srt"), 10);

        assertThat(locator.locateLargestVideo(root))
                .hasValueSatisfying(p -> assertThat(p.getFileName()).hasToString("movie.mp4"));
    }

    @Test
    void picksTheLargestVideoWhenSeveralExist(@TempDir Path root) throws IOException {
        write(root.resolve("sample.mp4"), 100);
        write(root.resolve("feature.mkv"), 9_000);

        assertThat(locator.locateLargestVideo(root))
                .hasValueSatisfying(p -> assertThat(p.getFileName()).hasToString("feature.mkv"));
    }

    @Test
    void ignoresExtensionsThatAreNotConfigured(@TempDir Path root) throws IOException {
        write(root.resolve("readme.txt"), 100);
        write(root.resolve("cover.jpg"), 100);

        assertThat(locator.locateLargestVideo(root)).isEmpty();
    }

    @Test
    void returnsEmptyForAnEmptyDownload(@TempDir Path root) {
        assertThat(locator.locateLargestVideo(root)).isEmpty();
    }

    @Test
    void acceptsASingleFileDownloadThatIsNotADirectory(@TempDir Path root) throws IOException {
        Path single = write(root.resolve("single.mp4"), 42);

        assertThat(locator.locateLargestVideo(single)).hasValue(single);
    }

    @Test
    void matchesExtensionsCaseInsensitively(@TempDir Path root) throws IOException {
        write(root.resolve("SHOUTING.MP4"), 42);

        assertThat(locator.locateLargestVideo(root)).isPresent();
    }

    private static Path write(Path path, int bytes) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.write(path, new byte[bytes]);
    }

    private static StreamingProperties propertiesWith(String... extensions) {
        return new StreamingProperties(
                new StreamingProperties.Storage(Path.of("target/test/downloads"), Path.of("target/test/hls")),
                new StreamingProperties.Ffmpeg(
                        "/bin/true", "/bin/true", Duration.ofSeconds(1), Duration.ofSeconds(4), List.of()),
                new StreamingProperties.Torrent(
                        List.of(extensions), Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new StreamingProperties.Transcoding(
                        new StreamingProperties.Transcoding.Pool(1, 1, 1, "test-")),
                new StreamingProperties.Web(List.of()));
    }
}
