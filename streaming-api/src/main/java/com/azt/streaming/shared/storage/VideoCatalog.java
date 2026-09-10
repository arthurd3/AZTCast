package com.azt.streaming.shared.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * What has finished transcoding and can be watched, read from the disk that holds it.
 *
 * <p>Deliberately not built on job state. Job records are per-process when Redis is off (the
 * default) and expire under a TTL when it is on, while the media outlives both — so a catalogue
 * drawn from jobs would go empty after a restart with videos still sitting in the HLS root. The
 * filesystem is the store (ADR-0003); this reads it.
 *
 * <p>A directory it cannot make sense of is logged and skipped rather than failing the listing. One
 * unreadable video should cost you that video, not the whole library.
 */
@Component
@Slf4j
public class VideoCatalog {

    /** Sidecar holding what the pipeline knew and the HLS output does not record. */
    static final String METADATA_FILE = "meta.json";

    /**
     * Sidecar marking a video as one to keep. Its <em>existence</em> is the flag; the contents are
     * not read.
     *
     * <p>A file rather than a field in {@code meta.json} because the reaper is the only thing that
     * has to consult it, and {@code Files.exists} answers that without parsing JSON on every
     * directory of every hourly sweep. A file rather than a row in Redis for the same reason the
     * catalogue reads the disk at all: Redis is optional and expires things, and a video whose
     * "keep" flag quietly aged out would be deleted by the very mechanism it was meant to escape.
     */
    static final String KEEP_FILE = "keep.json";

    private static final String TITLE_FIELD = "title";

    /**
     * A rendition playlist, as {@code FfmpegCommandBuilder} names them.
     *
     * <p>Matching the shape rather than listing every {@code *.m3u8} keeps stray files out of the
     * advertised ladder: media directories in the wild have picked up hand-made playlists, and
     * offering {@code cur} or {@code long} as a quality would be a lie about what the encoder wrote.
     */
    private static final Pattern RENDITION = Pattern.compile("\\d+p");

    private static final String PLAYLIST_SUFFIX = ".m3u8";

    private final MediaStorage mediaStorage;
    private final ObjectMapper objectMapper;

    public VideoCatalog(MediaStorage mediaStorage, ObjectMapper objectMapper) {
        this.mediaStorage = mediaStorage;
        this.objectMapper = objectMapper;
    }

    /** Every playable video, newest first. */
    public List<CatalogEntry> list() {
        return mediaStorage.listReadyVideoDirectories().stream()
                .map(this::describe)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(CatalogEntry::readyAt).reversed())
                .toList();
    }

    /**
     * Records what a video should be called, next to the media it names.
     *
     * <p>The sidecar lives inside the video's own directory so the reaper deletes it with everything
     * else: metadata and media then share one lifetime and cannot drift apart.
     *
     * <p>Never throws. A title is a nicety; an ingestion that already downloaded and transcoded a
     * torrent must not be failed because a small file could not be written.
     */
    public void record(String videoId, String title) {
        try {
            Path file = mediaStorage.hlsDirectoryFor(videoId).resolve(METADATA_FILE);
            objectMapper.writeValue(file.toFile(), Map.of(TITLE_FIELD, title));
        } catch (IOException | RuntimeException e) {
            log.warn("Could not record metadata for {}", videoId, e);
        }
    }

    /**
     * Marks a video to be kept, or stops keeping it.
     *
     * <p>Idempotent both ways. Returns false only when the video has no directory to mark, which is
     * how the controller tells a real id from one that has already been reaped.
     */
    public boolean setKept(String videoId, boolean kept) {
        // resolveHlsAsset, not hlsDirectoryFor: the latter creates the directory on demand, so
        // asking to keep an id that does not exist used to answer "no such video" and leave an
        // empty directory behind — one per request, from an endpoint anyone can call.
        Optional<Path> playlist = mediaStorage.resolveHlsAsset(videoId, MediaStorage.MASTER_PLAYLIST);
        if (playlist.isEmpty()) {
            return false;
        }
        Path marker = playlist.get().resolveSibling(KEEP_FILE);
        try {
            if (kept) {
                // Written with a timestamp so the file says why it is there when someone finds it
                // in a backup, even though nothing reads the contents back.
                objectMapper.writeValue(marker.toFile(), Map.of("keptAt", Instant.now().toString()));
            } else {
                Files.deleteIfExists(marker);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Could not set kept={} for {}", kept, videoId, e);
            return false;
        }
    }

    private Optional<CatalogEntry> describe(Path directory) {
        String videoId = directory.getFileName().toString();
        try {
            Instant readyAt =
                    Files.getLastModifiedTime(directory.resolve(MediaStorage.MASTER_PLAYLIST)).toInstant();

            List<Path> files;
            try (Stream<Path> entries = Files.list(directory)) {
                files = entries.filter(Files::isRegularFile).toList();
            }

            List<String> qualities = files.stream()
                    .map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(PLAYLIST_SUFFIX))
                    .map(name -> name.substring(0, name.length() - PLAYLIST_SUFFIX.length()))
                    .filter(name -> RENDITION.matcher(name).matches())
                    .sorted(Comparator.comparingInt(VideoCatalog::heightOf).reversed())
                    .toList();

            long sizeBytes = 0;
            boolean hasPoster = false;
            boolean kept = false;
            for (Path file : files) {
                sizeBytes += Files.size(file);
                String name = file.getFileName().toString();
                hasPoster |= name.equals(MediaStorage.POSTER);
                kept |= name.equals(KEEP_FILE);
            }

            return Optional.of(new CatalogEntry(
                    videoId, titleOf(directory), readyAt, qualities, sizeBytes, hasPoster, kept));
        } catch (IOException e) {
            log.warn("Skipping {}: could not read its directory", videoId, e);
            return Optional.empty();
        }
    }

    /**
     * The title from the sidecar, or null if there is not a usable one.
     *
     * <p>Read defensively on purpose: this file is optional, is written by an older version of this
     * class on most directories, and can be truncated by a crash mid-write. None of that is worth a
     * failed listing.
     */
    private String titleOf(Path directory) {
        Path file = directory.resolve(METADATA_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            String title = objectMapper.readTree(file.toFile()).path(TITLE_FIELD).asText(null);
            return title == null || title.isBlank() ? null : title;
        } catch (IOException | RuntimeException e) {
            log.warn("Ignoring unreadable metadata for {}", directory.getFileName(), e);
            return null;
        }
    }

    /** Leading digits of a rendition name, so 1080p sorts above 720p rather than beside 240p. */
    private static int heightOf(String quality) {
        int end = 0;
        while (end < quality.length() && Character.isDigit(quality.charAt(end))) {
            end++;
        }
        try {
            return end == 0 ? -1 : Integer.parseInt(quality.substring(0, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
