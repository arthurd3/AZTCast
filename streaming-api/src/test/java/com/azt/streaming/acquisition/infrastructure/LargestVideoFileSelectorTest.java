package com.azt.streaming.acquisition.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import bt.metainfo.Torrent;
import bt.metainfo.TorrentFile;
import bt.torrent.fileselector.FilePriority;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LargestVideoFileSelectorTest {

    private static final List<String> EXTENSIONS = List.of("mp4", "mkv", "avi");

    private static TorrentFile file(String path, long size) {
        TorrentFile file = org.mockito.Mockito.mock(TorrentFile.class);
        given(file.getSize()).willReturn(size);
        given(file.getPathElements()).willReturn(List.of(path.split("/")));
        return file;
    }

    private static Torrent torrent(TorrentFile... files) {
        Torrent torrent = org.mockito.Mockito.mock(Torrent.class);
        given(torrent.getFiles()).willReturn(List.of(files));
        given(torrent.getName()).willReturn("Some.Pack");
        return torrent;
    }

    @Test
    @DisplayName("keeps the largest video and skips everything else")
    void keepsOnlyTheLargestVideo() {
        // A season pack: this pipeline transcodes one file and VideoFileLocator picks the largest,
        // so the other nine were always going to be downloaded and then ignored.
        TorrentFile wanted = file("Season/E02.mkv", 2_000_000_000L);
        LargestVideoFileSelector selector = new LargestVideoFileSelector(EXTENSIONS);

        selector.prime(torrent(file("Season/E01.mkv", 1_000_000_000L), wanted, file("Season/readme.nfo", 1024)));

        assertThat(selector.prioritize(wanted)).isEqualTo(FilePriority.HIGH_PRIORITY);
        assertThat(selector.prioritize(file("Season/E01.mkv", 1_000_000_000L))).isEqualTo(FilePriority.SKIP);
        assertThat(selector.prioritize(file("Season/readme.nfo", 1024))).isEqualTo(FilePriority.SKIP);
    }

    @Test
    @DisplayName("downloads everything when it was never told what the torrent holds")
    void unprimedTakesEverything() {
        // The failure this class must fall towards. A wrong SKIP is a torrent that completes with
        // no video in it, which fails the ingestion far downstream with a confusing message;
        // downloading too much is merely slow. If the library ever stops calling prime() before it
        // chooses files, this is what happens instead.
        LargestVideoFileSelector selector = new LargestVideoFileSelector(EXTENSIONS);

        assertThat(selector.prioritize(file("anything.mkv", 5))).isEqualTo(FilePriority.NORMAL_PRIORITY);
    }

    @Test
    @DisplayName("skips nothing in a torrent with no recognised video")
    void noVideoMeansNoSkipping() {
        // Refusing to guess: the extension list is a heuristic, and a torrent whose video is in a
        // container nobody listed should still download rather than arrive empty.
        LargestVideoFileSelector selector = new LargestVideoFileSelector(EXTENSIONS);

        selector.prime(torrent(file("disc.iso", 900), file("notes.txt", 10)));

        assertThat(selector.prioritize(file("disc.iso", 900))).isEqualTo(FilePriority.NORMAL_PRIORITY);
    }

    @Test
    @DisplayName("leaves a single-file torrent alone")
    void singleFileTorrentIsUntouched() {
        LargestVideoFileSelector selector = new LargestVideoFileSelector(EXTENSIONS);

        selector.prime(torrent(file("Movie.mkv", 3_000_000_000L)));

        assertThat(selector.prioritize(file("Movie.mkv", 3_000_000_000L))).isEqualTo(FilePriority.NORMAL_PRIORITY);
    }

    @Test
    void matchesExtensionsCaseInsensitively() {
        TorrentFile shouty = file("Season/E02.MKV", 2_000L);
        LargestVideoFileSelector selector = new LargestVideoFileSelector(EXTENSIONS);

        selector.prime(torrent(file("Season/E01.mkv", 1_000L), shouty));

        assertThat(selector.prioritize(shouty)).isEqualTo(FilePriority.HIGH_PRIORITY);
    }
}
