package com.azt.streaming.transcoding.infrastructure;

import com.azt.streaming.shared.config.StreamingProperties;
import com.azt.streaming.transcoding.domain.HlsRendition;
import com.azt.streaming.transcoding.domain.PlannedRendition;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;

/**
 * Builds the ffmpeg argument list for the whole ladder, as a single invocation.
 *
 * <p>It used to build one command per rung, and the transcoder ran them in a loop. That decoded the
 * source once per rung: a five-rung ladder decoded the same file five times. One command with a
 * {@code split} filter decodes once and feeds every scaler from it.
 *
 * <p>Deliberately a pure function of its inputs, so the command can be asserted in a unit test
 * without a video file, a subprocess or a Spring context.
 *
 * <h2>Two constraints that dictate the shape of this command</h2>
 *
 * <p><b>Filenames must stay flat.</b> The usual multi-rung recipe writes {@code stream_0/playlist
 * .m3u8}. The playback contract is {@code /api/v1/stream/&#123;videoId&#125;/&#123;file&#125;} where
 * {@code file} is a <em>single</em> path segment, so a subdirectory is simply unreachable — the
 * mapping does not match it. The {@code name:} key in {@code -var_stream_map} is what makes
 * {@code %v} expand to the rung name rather than an index, giving {@code 720p.m3u8} and
 * {@code 720p_000.m4s} exactly as before.
 *
 * <p><b>No {@code -master_pl_name}.</b> ffmpeg would happily write the master playlist, but it
 * writes it when the encode <em>starts</em>. The presence of {@code master.m3u8} is this service's
 * "ladder is ready" signal, so that would make the sentinel fire immediately and hand players a
 * master pointing at variants that do not exist yet. Omitting the flag leaves ffmpeg writing only
 * the variants; {@link MasterPlaylistWriter} still writes the master, last.
 */
@Component
public class FfmpegCommandBuilder {

    /**
     * Poster width in pixels. Twice the ~240px the library renders a card at, so the thumbnail is
     * not soft on a high-density display. Height follows the source aspect ratio.
     */
    private static final int POSTER_WIDTH = 480;

    private final String binary;
    private final String videoCodec;
    private final Duration segmentDuration;

    public FfmpegCommandBuilder(StreamingProperties properties) {
        this.binary = properties.ffmpeg().binary();
        this.videoCodec = properties.ffmpeg().videoCodec();
        this.segmentDuration = properties.ffmpeg().segmentDuration();
    }

    /**
     * @param inputFile source media
     * @param outputDirectory folder that will hold every playlist, init segment and segment
     * @param ladder the rungs to produce, in the order they should appear, each already marked as
     *     copied or encoded by {@link com.azt.streaming.transcoding.domain.LadderPlanner}
     * @param includeAudio whether the source has an audio stream to map; a torrent may not, and
     *     {@code -map a:0} against a file with no audio fails the whole encode
     */
    public List<String> build(
            Path inputFile, Path outputDirectory, List<PlannedRendition> ladder, boolean includeAudio) {
        List<String> command = new ArrayList<>();
        command.add(binary);
        command.add("-i");
        command.add(inputFile.toString());

        List<PlannedRendition> encodedRungs =
                ladder.stream().filter(rung -> !rung.copyVideo()).toList();

        // Omitted entirely when every rung is copied. An empty filter graph is a parse error, and a
        // ladder with nothing to scale is reachable: a source shorter than the shortest configured
        // rung, already in H.264, needs one copied rung and no encoder at all.
        if (!encodedRungs.isEmpty()) {
            command.add("-filter_complex");
            command.add(filterGraph(encodedRungs));
        }

        int encodedIndex = 0;
        for (int i = 0; i < ladder.size(); i++) {
            PlannedRendition planned = ladder.get(i);
            HlsRendition rendition = planned.rendition();

            if (planned.copyVideo()) {
                // Straight off the input, not out of the filter graph: a copied stream is never
                // decoded, so there is no frame for a filter to receive.
                command.addAll(List.of("-map", "0:v:0", "-c:v:" + i, "copy"));
                continue;
            }

            command.addAll(List.of(
                    "-map", "[v%dout]".formatted(encodedIndex),
                    "-c:v:" + i, videoCodec,
                    // Per stream, and not optional. Without it libopenh264 drops to Constrained
                    // Baseline without failing, and the CODECS the master advertises becomes false.
                    "-profile:v:" + i, "main",
                    "-b:v:" + i, kbps(rendition.videoBitrateKbps()),
                    "-maxrate:v:" + i, kbps(rendition.maxrateKbps()),
                    "-bufsize:v:" + i, kbps(rendition.bufsizeKbps()),
                    // Keyframes by time, not by -g frame count. The usual advice is
                    // `-g N -keyint_min N -sc_threshold 0`, which assumes a known frame rate — but
                    // the source here is an arbitrary torrent and may well be variable frame rate,
                    // where a frame count is the wrong unit entirely. Forcing on the segment
                    // boundary makes every segment start on a keyframe at any frame rate.
                    //
                    // Per stream rather than global now, because a global one would also be aimed
                    // at the copied rung, where there is no encoder to instruct.
                    "-force_key_frames:v:" + i, keyFrameExpression()));
            encodedIndex++;
        }

        if (includeAudio) {
            for (int i = 0; i < ladder.size(); i++) {
                PlannedRendition planned = ladder.get(i);
                command.addAll(List.of("-map", "a:0"));
                if (planned.copyAudio()) {
                    command.addAll(List.of("-c:a:" + i, "copy"));
                    continue;
                }
                command.addAll(List.of(
                        "-c:a:" + i, "aac",
                        "-b:a:" + i, kbps(planned.rendition().audioBitrateKbps()),
                        // Per stream for the same reason as the keyframes: -ac and -ar are
                        // instructions to a resampler, and a copied track has none.
                        "-ac:a:" + i, "2",
                        "-ar:a:" + i, "48000"));
            }
        }

        command.addAll(List.of(
                "-f", "hls",
                "-hls_time", String.valueOf(segmentDuration.toSeconds()),
                "-hls_playlist_type", "vod",
                // Each segment decodable without its predecessors — the precondition for the player
                // switching rungs mid-stream, and it emits EXT-X-INDEPENDENT-SEGMENTS to say so.
                // Still true of a copied rung: its segments break on the keyframes the source
                // already had. What is no longer guaranteed is that they break at the *same* points
                // as the encoded rungs, because nothing put those keyframes where we would choose.
                "-hls_flags", "independent_segments",
                "-hls_segment_type", "fmp4",
                "-hls_fmp4_init_filename", "%v_init.mp4",
                "-hls_segment_filename", outputDirectory.resolve("%v_%03d.m4s").toString(),
                "-var_stream_map", variantStreamMap(ladder, includeAudio),
                outputDirectory.resolve("%v.m3u8").toString()));

        return List.copyOf(command);
    }

    private String keyFrameExpression() {
        return "expr:gte(t,n_forced*%d)".formatted(segmentDuration.toSeconds());
    }

    /**
     * A single still frame, for the library to show as a thumbnail.
     *
     * <p>Uses the {@code thumbnail} filter rather than taking whatever frame the seek lands on: it
     * scores a batch of frames and picks the most representative, which is what keeps posters from
     * being the black frame or fade-in that so many files open on.
     *
     * <p>{@code seek} is optional because nothing here knows the source's duration —
     * {@link com.azt.streaming.transcoding.domain.ProbedVideo} does not carry one. Seeking past the
     * end fails the command rather than producing a frame, so the caller asks for an offset first
     * and retries from the start if that happens.
     *
     * @param seek where to start reading, or null to start at the beginning
     */
    public List<String> buildPoster(Path inputFile, Path outputFile, Duration seek) {
        List<String> command = new ArrayList<>();
        command.add(binary);
        command.add("-y");
        if (seek != null) {
            // Before -i, so ffmpeg seeks the input rather than decoding and discarding up to it.
            command.addAll(List.of("-ss", String.valueOf(seek.toSeconds())));
        }
        command.addAll(List.of(
                "-i", inputFile.toString(),
                // The first video stream specifically: a container may also carry cover art, and
                // -frames:v against an unmapped input can pick it.
                "-map", "0:v:0",
                "-an",
                "-vf", "thumbnail,scale=w=%d:h=-2".formatted(POSTER_WIDTH),
                "-frames:v", "1",
                "-q:v", "4",
                "-f", "image2",
                outputFile.toString()));
        return List.copyOf(command);
    }

    /**
     * {@code [0:v]split=N[v0]…;[v0]scale=w=W:h=H[v0out];…} — one decode, N scalers.
     *
     * <p>N counts only the rungs being encoded. A copied rung never reaches the graph, so giving it
     * a branch would leave an output nothing consumes, which ffmpeg rejects.
     */
    private static String filterGraph(List<PlannedRendition> encodedRungs) {
        String split = IntStream.range(0, encodedRungs.size())
                .mapToObj("[v%d]"::formatted)
                .collect(Collectors.joining());
        String scalers = IntStream.range(0, encodedRungs.size())
                .mapToObj(i -> "[v%d]scale=w=%d:h=%d[v%dout]"
                        .formatted(
                                i,
                                encodedRungs.get(i).rendition().width(),
                                encodedRungs.get(i).rendition().height(),
                                i))
                .collect(Collectors.joining(";"));
        return "[0:v]split=%d%s;%s".formatted(encodedRungs.size(), split, scalers);
    }

    /** {@code v:0,a:0,name:1080p v:1,a:1,name:720p …} — the {@code name:} is what keeps files flat. */
    private static String variantStreamMap(List<PlannedRendition> ladder, boolean includeAudio) {
        return IntStream.range(0, ladder.size())
                .mapToObj(i -> includeAudio
                        ? "v:%d,a:%d,name:%s".formatted(i, i, ladder.get(i).rendition().name())
                        : "v:%d,name:%s".formatted(i, ladder.get(i).rendition().name()))
                .collect(Collectors.joining(" "));
    }

    private static String kbps(int value) {
        return value + "k";
    }
}
