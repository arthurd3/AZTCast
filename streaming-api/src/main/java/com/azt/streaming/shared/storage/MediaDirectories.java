package com.azt.streaming.shared.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/**
 * The one recursive delete in this codebase.
 *
 * <p>It lived inside the reaper as a private method while it had one caller. Every path that removes
 * media goes through it now — {@link DownloadReaper} sweeping abandoned torrents, storage discarding
 * a source once its transcode is verified, storage clearing up after an encode that failed halfway,
 * and the deliberate delete of a whole video — and a second hand-written {@code walk} +
 * {@code reverseOrder} + {@code deleteIfExists} is not a thing to have two of.
 */
@Slf4j
final class MediaDirectories {

    private MediaDirectories() {}

    /**
     * Removes a directory and everything under it.
     *
     * @return whether the whole tree went; false leaves a warning in the log and the caller free to
     *     carry on, because a directory that would not delete is an operational problem rather than
     *     a reason to fail whatever asked
     */
    static boolean deleteRecursively(Path directory) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
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
