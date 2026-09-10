package com.azt.streaming.transcoding.infrastructure;

import com.azt.streaming.transcoding.domain.SubtitlePlan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Turns the source's text subtitle tracks into WebVTT files and the playlists that point at them.
 *
 * <p>Every one of these tracks was dropped in silence until now. The ladder command mapped
 * {@code 0:v:0} and one audio stream, so a release carrying four subtitle tracks — original, SDH and
 * two dubs, which is an ordinary shape — arrived on disk complete and was transcoded into nothing.
 *
 * <p>A failure here never fails the ingestion. A video with no captions is a video; a video that did
 * not transcode is not.
 */
@Component
@Slf4j
public class SubtitlePublisher {

    /**
     * Converting a text stream is measured in kilobytes and finishes in well under a second on any
     * real file. A deadline this short exists so a pathological track cannot hold a transcoding
     * thread for the ladder's full thirty minutes.
     */
    private static final Duration EXTRACTION_TIMEOUT = Duration.ofMinutes(2);

    private final FfmpegCommandBuilder commandBuilder;
    private final ProcessRunner processRunner;

    public SubtitlePublisher(FfmpegCommandBuilder commandBuilder, ProcessRunner processRunner) {
        this.commandBuilder = commandBuilder;
        this.processRunner = processRunner;
    }

    /**
     * Publishes every track it can, and returns the ones that made it.
     *
     * <p>The return value is what the master playlist advertises, so a track that failed to extract
     * is simply never mentioned — rather than mentioned and then 404ing when a viewer selects it.
     *
     * @param durationSeconds the source's duration, which becomes the single segment's EXTINF
     */
    public List<SubtitlePlan> publish(
            Path inputFile, Path outputDirectory, List<SubtitlePlan> planned, double durationSeconds) {
        List<SubtitlePlan> published = new ArrayList<>(planned.size());
        for (SubtitlePlan subtitle : planned) {
            Path vtt = outputDirectory.resolve(subtitle.vttFileName());
            try {
                processRunner.run(commandBuilder.buildSubtitle(inputFile, vtt, subtitle), EXTRACTION_TIMEOUT);
                // An empty file is what a track full of nothing but formatting produces, and
                // advertising it gives a viewer a caption option that shows no captions.
                if (Files.size(vtt) == 0) {
                    Files.deleteIfExists(vtt);
                    log.debug("Subtitle track {} converted to an empty file; skipping it", subtitle.sourceIndex());
                    continue;
                }
                Files.writeString(
                        outputDirectory.resolve(subtitle.playlistFileName()),
                        renderPlaylist(subtitle, durationSeconds),
                        StandardCharsets.UTF_8);
                published.add(subtitle);
            } catch (IOException | RuntimeException e) {
                log.warn(
                        "Could not publish subtitle track {} ({}); leaving it out",
                        subtitle.sourceIndex(),
                        subtitle.label(),
                        e);
                quietlyDelete(vtt);
            }
        }
        if (!published.isEmpty()) {
            log.info(
                    "Published {} subtitle track(s): {}",
                    published.size(),
                    published.stream().map(SubtitlePlan::label).toList());
        }
        return List.copyOf(published);
    }

    /**
     * A VOD playlist whose single segment is the whole WebVTT file.
     *
     * <p>HLS wants subtitles segmented alongside the media in the general case. For video on demand
     * this shape is both legal and universally supported, and it avoids inventing cue-splitting
     * logic to solve a problem — seeking into a caption track without downloading it all — that does
     * not exist at the size these files are.
     */
    String renderPlaylist(SubtitlePlan subtitle, double durationSeconds) {
        double duration = durationSeconds > 0 ? durationSeconds : 1;
        // Locale.ROOT, not the default: `%.3f` under a pt_BR locale writes "3,999", and a comma in
        // an EXTINF is a playlist no player will parse.
        return String.format(
                Locale.ROOT,
                """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-PLAYLIST-TYPE:VOD
                #EXT-X-TARGETDURATION:%d
                #EXT-X-MEDIA-SEQUENCE:0
                #EXTINF:%.3f,
                %s
                #EXT-X-ENDLIST
                """,
                (long) Math.ceil(duration),
                duration,
                subtitle.vttFileName());
    }

    private static void quietlyDelete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("Could not remove the partial subtitle file {}", file, e);
        }
    }
}
