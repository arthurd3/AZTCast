package com.azt.streaming.acquisition.infrastructure;

import bt.metainfo.Torrent;
import bt.metainfo.TorrentFile;
import bt.torrent.fileselector.FilePriority;
import bt.torrent.fileselector.FilePrioritySkipSelector;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Downloads the one video this pipeline will actually transcode, and skips the rest of the torrent.
 *
 * <p>{@link VideoFileLocator} already picks the largest video file out of a finished download and
 * ignores everything beside it, so the bytes spent on the others were never going to be served. On a
 * single-file torrent that is nothing; on a season pack it is nine tenths of the transfer, and it is
 * the difference between waiting for one episode and waiting for ten.
 *
 * <p><b>Stateful, and one instance per download.</b> The library's hook is
 * {@link FilePrioritySkipSelector#prioritize(TorrentFile)}, which is asked about one file at a time
 * and cannot see the others — but "largest" is a fact about the whole list. So the list arrives
 * separately, through {@link #prime(Torrent)} on the {@code afterTorrentFetched} callback, which the
 * library invokes before it chooses files.
 *
 * <p><b>Unprimed means take everything.</b> If that callback ordering ever changes, the failure has
 * to land on the side of downloading too much: a wrong {@code SKIP} is a torrent that completes
 * without the video in it, which fails the ingestion far downstream with a confusing message. Too
 * much is merely slow.
 */
@Slf4j
public class LargestVideoFileSelector implements FilePrioritySkipSelector {

    private final Set<String> videoExtensions;

    /**
     * Path elements of the file to keep, or null until primed.
     *
     * <p>Volatile because it is written on the library thread that fetches metadata and read on
     * whichever thread chooses files, with no happens-before between them otherwise.
     */
    private volatile List<String> wanted;

    public LargestVideoFileSelector(List<String> videoExtensions) {
        this.videoExtensions =
                videoExtensions.stream().map(e -> e.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    /** Works out which file to keep, once the torrent's metadata is known. */
    public void prime(Torrent torrent) {
        List<TorrentFile> files = torrent.getFiles();
        if (files.size() <= 1) {
            // Nothing to skip. Leaving it unprimed keeps prioritize() on its take-everything path
            // rather than relying on the single file matching its own name.
            return;
        }

        Optional<TorrentFile> largest = files.stream()
                .filter(this::isVideo)
                .max(Comparator.comparingLong(TorrentFile::getSize));

        if (largest.isEmpty()) {
            log.warn(
                    "Torrent {} has {} files and no recognised video among them; downloading all of it",
                    torrent.getName(),
                    files.size());
            return;
        }

        wanted = List.copyOf(largest.get().getPathElements());
        log.info(
                "Torrent {}: downloading {} ({} MiB) and skipping {} other file(s)",
                torrent.getName(),
                String.join("/", wanted),
                largest.get().getSize() / (1024 * 1024),
                files.size() - 1);
    }

    @Override
    public FilePriority prioritize(TorrentFile file) {
        List<String> target = wanted;
        if (target == null) {
            return FilePriority.NORMAL_PRIORITY;
        }
        return target.equals(file.getPathElements()) ? FilePriority.HIGH_PRIORITY : FilePriority.SKIP;
    }

    private boolean isVideo(TorrentFile file) {
        List<String> path = file.getPathElements();
        if (path.isEmpty()) {
            return false;
        }
        String name = path.get(path.size() - 1);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && videoExtensions.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
