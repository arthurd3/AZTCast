package com.azt.streaming.transcoding.infrastructure;

import com.azt.streaming.transcoding.domain.AudioPlan;
import com.azt.streaming.shared.storage.MediaStorage;
import com.azt.streaming.transcoding.domain.EncodedAudio;
import com.azt.streaming.transcoding.domain.EncodedRendition;
import com.azt.streaming.transcoding.domain.LadderReport;
import com.azt.streaming.transcoding.domain.SubtitlePlan;
import com.azt.streaming.transcoding.domain.VariantPlaylist;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Proves a finished ladder is actually playable before anything is told it exists.
 *
 * <p>{@code master.m3u8} is this service's readiness sentinel — the catalogue lists a video if and
 * only if that file is there. Until now it was written whenever ffmpeg exited zero, which is a
 * weaker claim than it looks: nothing had ever opened a variant playlist to check that the segments
 * it names are on disk, and nothing had checked that a browser could assemble any of it. Both of
 * those failed in production, the second one in a way that produced a video listed as ready that no
 * browser would play.
 *
 * <p>So the sentinel now means what everything downstream already assumed it meant.
 */
@Component
public class LadderIntegrity {

    /** {@code CODECS="avc1.640028,ec-3"} in a master playlist's variant line. */
    private static final Pattern CODECS = Pattern.compile("CODECS=\"([^\"]+)\"");

    /** {@code URI="audio.m3u8"} on an {@code EXT-X-MEDIA} rendition line. */
    private static final Pattern MEDIA_URI = Pattern.compile("URI=\"([^\"]+)\"");

    /** {@code CHANNELS="6"} on the audio rendition line — the only place the layout is written. */
    private static final Pattern AUDIO_CHANNELS =
            Pattern.compile("TYPE=AUDIO[^\n]*CHANNELS=\"(\\d+)\"");

    /**
     * Video codecs a mainstream browser decodes. AVC only, which is the same constraint
     * {@code ProbedSource.videoIsCopyable} applies at the other end of the pipeline.
     */
    private static final String WIDELY_PLAYABLE_VIDEO = "avc1.";

    /**
     * Inspects a ladder that was already published, reading the master playlist for its shape.
     *
     * <p>Unlike {@link #verify}, which is handed the plan that produced the output, this has only
     * the directory — which is the situation a repair is in, a week or a restart after the
     * ingestion that wrote it. The master playlist names every variant and every rendition, so it
     * is enough.
     */
    public LadderReport inspectPublished(Path directory) {
        Path master = directory.resolve(MediaStorage.MASTER_PLAYLIST);
        String rendered;
        try {
            rendered = Files.readString(master, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // No master at all: either the ladder never finished, or something removed it. Either
            // way there is nothing published to inspect, and the media cannot be vouched for.
            return new LadderReport(List.of("master.m3u8 is missing or unreadable"), false);
        }

        List<String> problems = new ArrayList<>();
        List<String> playlists = referencedPlaylists(rendered);
        if (playlists.isEmpty()) {
            problems.add("master.m3u8 references no playlists");
        }
        boolean mediaIntact = true;
        for (String playlist : playlists) {
            List<String> found = new ArrayList<>();
            boolean filesPresent = verifyVariant(directory, playlist, found);
            mediaIntact &= filesPresent;
            problems.addAll(found);
        }
        if (!hasWidelyPlayableVariant(rendered)) {
            problems.add("no variant is playable outside Apple's platforms — every CODECS attribute"
                    + " names something a mainstream browser cannot decode");
        }
        return new LadderReport(problems, mediaIntact && !playlists.isEmpty(), publishedAudio(rendered));
    }

    /**
     * The audio rendition as the master describes it: its codec, and how many channels it carries.
     *
     * <p>The codec is the part of a variant's {@code CODECS} that is not the video — the attribute
     * names the combination, so the audio identifier is whatever is left after the {@code avc1.}
     * entry. The channel count comes from the {@code EXT-X-MEDIA} line, which is the only place it
     * appears.
     *
     * <p>Read back rather than remembered because a repair runs long after the ingestion that
     * wrote it, on a host whose capabilities may have changed in between — which is exactly the
     * case this exists to catch.
     */
    private static Optional<LadderReport.PublishedAudio> publishedAudio(String rendered) {
        String codec = null;
        Matcher codecs = CODECS.matcher(rendered);
        while (codecs.find() && codec == null) {
            for (String entry : codecs.group(1).split(",")) {
                String trimmed = entry.strip();
                if (!trimmed.toLowerCase(Locale.ROOT).startsWith(WIDELY_PLAYABLE_VIDEO)) {
                    codec = trimmed;
                }
            }
        }
        if (codec == null) {
            return Optional.empty();
        }
        int channels = 0;
        Matcher media = AUDIO_CHANNELS.matcher(rendered);
        if (media.find()) {
            channels = Integer.parseInt(media.group(1));
        }
        return Optional.of(new LadderReport.PublishedAudio(codec, channels));
    }

    /**
     * Every media playlist the master points at: the variant URIs and the rendition group URIs.
     *
     * <p>A {@code URI="…"} attribute on an {@code EXT-X-MEDIA} line, or the bare line following an
     * {@code EXT-X-STREAM-INF}.
     */
    private static List<String> referencedPlaylists(String rendered) {
        List<String> playlists = new ArrayList<>();
        boolean expectingVariantUri = false;
        for (String line : rendered.lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.startsWith("#EXT-X-MEDIA")) {
                Matcher uri = MEDIA_URI.matcher(trimmed);
                if (uri.find() && !playlists.contains(uri.group(1))) {
                    playlists.add(uri.group(1));
                }
                continue;
            }
            if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                expectingVariantUri = true;
                continue;
            }
            if (expectingVariantUri && !trimmed.isEmpty() && !trimmed.startsWith("#")) {
                // The same rung can be advertised twice — once joined to an audio group and once
                // video-only — and it is one playlist either way.
                if (!playlists.contains(trimmed)) {
                    playlists.add(trimmed);
                }
                expectingVariantUri = false;
            }
        }
        return playlists;
    }

    /**
     * Checks {@code rendered} against what is on disk.
     *
     * @return every problem found, in reading order; empty means the ladder is sound
     */
    public List<String> verify(
            Path directory,
            String rendered,
            List<EncodedRendition> renditions,
            EncodedAudio audio,
            List<SubtitlePlan> subtitles) {
        List<String> problems = new ArrayList<>();

        for (EncodedRendition encoded : renditions) {
            verifyVariant(directory, encoded.rendition().playlistFileName(), problems);
        }
        if (audio.present()) {
            verifyVariant(directory, audio.plan().playlistFileName(), problems);
        }
        for (SubtitlePlan subtitle : subtitles) {
            requireReadable(directory, subtitle.playlistFileName(), problems);
            requireReadable(directory, subtitle.vttFileName(), problems);
        }
        if (renditions.isEmpty()) {
            problems.add("the ladder has no renditions at all");
        }
        if (!hasWidelyPlayableVariant(rendered)) {
            // The defect this exists for. A ladder of five sound H.264 rungs was unplayable in
            // every browser but Safari, because each variant's CODECS also named the E-AC-3 audio
            // travelling beside it and an unsupported codec disqualifies the whole variant.
            problems.add("no variant is playable outside Apple's platforms — every CODECS attribute"
                    + " names something a mainstream browser cannot decode");
        }
        return List.copyOf(problems);
    }

    /**
     * Whether at least one variant declares nothing but codecs a mainstream browser decodes.
     *
     * <p>Read back off the rendered playlist rather than derived from the plan, deliberately: the
     * plan is what was intended and this is what will be served. A writer that stopped emitting the
     * video-only family would still satisfy an assertion about the plan.
     */
    private static boolean hasWidelyPlayableVariant(String rendered) {
        Matcher matcher = CODECS.matcher(rendered);
        while (matcher.find()) {
            if (playableEverywhere(matcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    private static boolean playableEverywhere(String codecs) {
        for (String codec : codecs.split(",")) {
            String trimmed = codec.strip();
            boolean video = trimmed.toLowerCase(Locale.ROOT).startsWith(WIDELY_PLAYABLE_VIDEO);
            if (!video && !AudioPlan.isWidelyPlayableCodec(trimmed)) {
                return false;
            }
        }
        return !codecs.isBlank();
    }

    /**
     * Opens one variant playlist and checks that every file it names is there and has bytes in it.
     *
     * <p>A zero-length segment counts as missing. ffmpeg produces them when it is killed between
     * creating a file and writing to it, and a player treats one as a decode error rather than as a
     * gap it can skip.
     */
    private boolean verifyVariant(Path directory, String playlistName, List<String> problems) {
        Path playlist = directory.resolve(playlistName);
        List<String> lines;
        try {
            lines = Files.readAllLines(playlist, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // NoSuchFileException's message is only the path, which reads as though the path were
            // the complaint. Say what actually happened.
            problems.add("%s is missing or unreadable (%s)".formatted(playlistName, e.getClass().getSimpleName()));
            return false;
        }

        VariantPlaylist parsed = VariantPlaylist.parse(lines);
        if (parsed.segments().isEmpty()) {
            problems.add(playlistName + " names no segments");
        }
        if (!parsed.complete()) {
            // A VOD playlist without ENDLIST is one ffmpeg was still writing. A player will wait
            // for a segment that is never coming.
            problems.add(playlistName + " has no #EXT-X-ENDLIST, so it was never finished");
        }
        if (parsed.needsInitSegment() && parsed.initSegment().isEmpty()) {
            problems.add(playlistName + " declares no #EXT-X-MAP; an fMP4 variant is undecodable"
                    + " without its init segment");
        }

        int missing = 0;
        String firstMissing = null;
        for (String file : parsed.referencedFiles()) {
            if (!isReadable(directory.resolve(file))) {
                missing++;
                if (firstMissing == null) {
                    firstMissing = file;
                }
            }
        }
        if (missing > 0) {
            problems.add("%s references %d missing or empty file(s), starting with %s"
                    .formatted(playlistName, missing, firstMissing));
        }
        return missing == 0;
    }

    private void requireReadable(Path directory, String fileName, List<String> problems) {
        if (!isReadable(directory.resolve(fileName))) {
            problems.add(fileName + " is missing or empty");
        }
    }

    private static boolean isReadable(Path file) {
        try {
            return Files.isRegularFile(file) && Files.size(file) > 0;
        } catch (IOException e) {
            return false;
        }
    }
}
