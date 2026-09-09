package com.azt.streaming.shared.storage;

import com.azt.streaming.shared.config.StreamingProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** {@link MediaStorage} over the local filesystem. */
@Component
@Slf4j
public class FileSystemMediaStorage implements MediaStorage {

    private final Path downloadsRoot;
    private final Path hlsRoot;

    public FileSystemMediaStorage(StreamingProperties properties) {
        // Normalised absolute paths, resolved once. Deliberately not toRealPath(): that throws if
        // the directory does not exist yet, and on a symlinked root it disagrees with the
        // normalised form of paths built later, which would produce 404s indistinguishable from
        // "this video is still transcoding".
        this.downloadsRoot = properties.storage().downloadsDir().toAbsolutePath().normalize();
        this.hlsRoot = properties.storage().hlsDir().toAbsolutePath().normalize();
    }

    /**
     * Creates both roots at startup.
     *
     * <p>This used to be {@code new File(hlsDir).mkdir()} with the return value ignored — a
     * single-level call against a two-level path, so it silently did nothing whenever the parent was
     * absent, and the service only worked on machines where the directory happened to exist already.
     */
    @PostConstruct
    void createRoots() {
        createDirectories(downloadsRoot);
        createDirectories(hlsRoot);
        log.info("Media roots ready: downloads={} hls={}", downloadsRoot, hlsRoot);
    }

    @Override
    public Path downloadDirectoryFor(String videoId) {
        Path directory = requireInside(downloadsRoot, videoId, "");
        createDirectories(directory);
        return directory;
    }

    @Override
    public Path hlsDirectoryFor(String videoId) {
        Path directory = requireInside(hlsRoot, videoId, "");
        createDirectories(directory);
        return directory;
    }

    @Override
    public Optional<Path> resolveHlsAsset(String videoId, String fileName) {
        Path asset;
        try {
            asset = requireInside(hlsRoot, videoId, fileName);
        } catch (IllegalArgumentException e) { // InvalidPathException is a subclass
            log.warn("Rejected HLS asset request outside the media root: videoId={} file={}", videoId, fileName);
            return Optional.empty();
        }
        return Files.isRegularFile(asset) ? Optional.of(asset) : Optional.empty();
    }

    @Override
    public List<Path> listReadyVideoDirectories() {
        if (!Files.isDirectory(hlsRoot)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(hlsRoot)) {
            return entries.filter(Files::isDirectory)
                    .filter(directory -> Files.isRegularFile(directory.resolve(MASTER_PLAYLIST)))
                    .toList();
        } catch (IOException e) {
            // An unreadable root is an operational problem, not the caller's. Answering "no videos"
            // keeps the listing endpoint up while it is investigated, which beats a 500 that tells a
            // viewer nothing they can act on.
            log.warn("Could not list {} for the catalogue", hlsRoot, e);
            return List.of();
        }
    }

    /**
     * Resolves {@code videoId/fileName} under {@code root} and proves the result stays there.
     *
     * <p>{@code resolve} with an absolute argument discards the base entirely, and {@code ..}
     * segments walk upward, so normalising and then re-checking containment is the part that
     * actually enforces the boundary.
     */
    private static Path requireInside(Path root, String videoId, String fileName) {
        Path candidate = root.resolve(videoId);
        if (!fileName.isEmpty()) {
            candidate = candidate.resolve(fileName);
        }
        Path normalised = candidate.normalize();
        if (!normalised.startsWith(root)) {
            throw new IllegalArgumentException(
                    "Path escapes the media root: " + videoId + "/" + fileName);
        }
        return normalised;
    }

    private static void createDirectories(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create media directory " + directory, e);
        }
    }
}
