package com.azt.streaming.acquisition.infrastructure;

import com.azt.streaming.shared.config.StreamingProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Finds the video inside a completed download: the largest file whose extension is configured as a
 * video type.
 *
 * <p>This used to be a private method calling {@code File.listFiles()}, which only looks one level
 * deep. Torrents commonly wrap their payload in a folder, so the single most common real-world case
 * — a directory containing the video — always returned empty and failed the download with "No video
 * file found in torrent". It walks the tree now.
 */
@Component
public class VideoFileLocator {

    private final List<String> videoExtensions;

    public VideoFileLocator(StreamingProperties properties) {
        this.videoExtensions =
                properties.torrent().videoExtensions().stream()
                        .map(extension -> extension.toLowerCase(Locale.ROOT))
                        .map(extension -> extension.startsWith(".") ? extension : "." + extension)
                        .toList();
    }

    /** Returns the largest video file anywhere beneath {@code root}, if there is one. */
    public Optional<Path> locateLargestVideo(Path root) {
        if (!Files.isDirectory(root)) {
            return Files.isRegularFile(root) && isVideo(root) ? Optional.of(root) : Optional.empty();
        }
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.filter(Files::isRegularFile)
                    .filter(this::isVideo)
                    .max(Comparator.comparingLong(VideoFileLocator::sizeOf));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan " + root + " for video files", e);
        }
    }

    private boolean isVideo(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return videoExtensions.stream().anyMatch(name::endsWith);
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1L;
        }
    }
}
