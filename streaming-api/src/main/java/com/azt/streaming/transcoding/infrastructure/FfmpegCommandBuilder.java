package com.azt.streaming.transcoding.infrastructure;

import com.azt.streaming.shared.config.StreamingProperties;
import com.azt.streaming.transcoding.domain.AudioPlan;
import com.azt.streaming.transcoding.domain.EncoderChoice;
import com.azt.streaming.transcoding.domain.HlsRendition;
import com.azt.streaming.transcoding.domain.PlannedRendition;
import com.azt.streaming.transcoding.domain.SubtitlePlan;
import com.azt.streaming.transcoding.domain.TranscodePlan;
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
 * scaling chain decodes once and feeds every encoder from it.
 *
 * <p>Deliberately a pure function of its inputs, so the command can be asserted in a unit test
 * without a video file, a subprocess or a Spring context. That is why it is handed an
 * {@link EncoderChoice} and a {@link TranscodePlan} rather than resolving either itself — both are
 * answers that require running ffmpeg to obtain.
 *
 * <h2>Constraints that dictate the shape of this command</h2>
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
 * the variants; {@link MasterPlaylistWriter} still writes the master, last — which is also what lets
 * it join a video variant to its audio group, something ffmpeg's own writer cannot express for a
 * ladder whose CODECS strings are measured after the fact.
 *
 * <p><b>One audio rendition, in a group.</b> Every rung used to map {@code a:0} and encode it
 * separately: five AAC encodes of one track, and five copies of it in the segments. The
 * {@code agroup:} key puts it in its own variant that every rung references instead.
 */
@Component
public class FfmpegCommandBuilder {

    /**
     * Poster width in pixels. Twice the ~240px the library renders a card at, so the thumbnail is
     * not soft on a high-density display. Height follows the source aspect ratio.
     */
    private static final int POSTER_WIDTH = 480;

    private final String binary;
    private final Duration segmentDuration;

    public FfmpegCommandBuilder(StreamingProperties properties) {
        this.binary = properties.ffmpeg().binary();
        this.segmentDuration = properties.ffmpeg().segmentDuration();
    }

    /**
     * @param inputFile source media
     * @param outputDirectory folder that will hold every playlist, init segment and segment
     * @param plan the rungs, the audio rendition and the GOP length decided for this source
     * @param encoder the video encoder resolved against what this build actually carries
     */
    public List<String> build(Path inputFile, Path outputDirectory, TranscodePlan plan, EncoderChoice encoder) {
        List<PlannedRendition> ladder = plan.renditions();
        List<String> command = new ArrayList<>(List.of(binary, "-hide_banner"));

        // Never read the terminal. Without it an ffmpeg that decides to ask something — "overwrite?"
        // on a retry into a directory that still holds a previous attempt — blocks on the parent's
        // stdin until the 30-minute deadline kills it, with nothing in the log to say why.
        command.add("-nostdin");
        command.add("-y");

        // Machine-readable progress on stdout instead of the human `frame=…` carousel on stderr, so
        // the job can report how far along the encode is. The transcoding status used to sit at a
        // hardcoded 100% for the entire encode.
        command.addAll(List.of("-nostats", "-progress", "pipe:1"));

        command.addAll(List.of("-i", inputFile.toString()));

        List<PlannedRendition> encodedRungs = ladder.stream().filter(rung -> !rung.copyVideo()).toList();

        // Omitted entirely when every rung is copied. An empty filter graph is a parse error, and a
        // ladder with nothing to scale is reachable: a source shorter than the shortest configured
        // rung, already in H.264, needs one copied rung and no encoder at all.
        if (!encodedRungs.isEmpty()) {
            command.addAll(List.of("-filter_complex", filterGraph(encodedRungs)));
        }

        int encodedIndex = 0;
        for (int i = 0; i < ladder.size(); i++) {
            PlannedRendition planned = ladder.get(i);
            if (planned.copyVideo()) {
                // Straight off the input, not out of the filter graph: a copied stream is never
                // decoded, so there is no frame for a filter to receive.
                command.addAll(List.of("-map", "0:v:0", "-c:v:" + i, "copy"));
                continue;
            }
            command.addAll(videoArgs(planned.rendition(), i, encodedIndex, encoder, plan.framesPerSegment()));
            encodedIndex++;
        }

        command.addAll(audioArgs(plan.audio()));

        // Subtitles and data streams are handled elsewhere or not at all, and saying so is cheaper
        // than discovering that a container's timecode track confused the muxer.
        command.addAll(List.of("-sn", "-dn"));

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
                "-var_stream_map", variantStreamMap(ladder, plan.audio()),
                outputDirectory.resolve("%v.m3u8").toString()));

        return List.copyOf(command);
    }

    /**
     * One encoded rung's arguments, all specified per output stream.
     *
     * <p>Per stream and not optional: aimed globally, every one of these would also be aimed at the
     * copied rung, where there is no encoder to obey them.
     */
    private List<String> videoArgs(
            HlsRendition rendition, int outputIndex, int filterIndex, EncoderChoice encoder, int framesPerSegment) {
        List<String> args = new ArrayList<>(List.of(
                "-map", "[v%dout]".formatted(filterIndex),
                "-c:v:" + outputIndex, encoder.name(),
                // Without it libopenh264 drops to Constrained Baseline without failing, and the
                // CODECS the master advertises becomes false.
                "-profile:v:" + outputIndex, "main",
                "-b:v:" + outputIndex, kbps(rendition.videoBitrateKbps()),
                "-maxrate:v:" + outputIndex, kbps(rendition.maxrateKbps()),
                "-bufsize:v:" + outputIndex, kbps(rendition.bufsizeKbps())));

        if (encoder.hasPreset()) {
            args.addAll(List.of("-preset:v:" + outputIndex, encoder.preset()));
        }

        // Keyframes by time is the authority, because the source is an arbitrary torrent and may
        // well be variable frame rate, where a frame count is the wrong unit entirely. Forcing on
        // the segment boundary makes every segment start on a keyframe at any frame rate.
        args.addAll(List.of("-force_key_frames:v:" + outputIndex, keyFrameExpression()));

        // -g and -keyint_min are the belt to that brace, and only emitted when the frame rate was
        // actually reported. They do not change where segments break — the muxer cuts at the first
        // keyframe at or after hls_time either way — but they cap the GOP so an encoder cannot run
        // hundreds of frames between keyframes inside one segment, and they stop it inventing
        // scene-change keyframes that cost bitrate and buy nothing at these rung sizes.
        if (framesPerSegment > 0) {
            args.addAll(List.of(
                    "-g:v:" + outputIndex, String.valueOf(framesPerSegment),
                    "-keyint_min:v:" + outputIndex, String.valueOf(framesPerSegment)));
        }
        return args;
    }

    /**
     * The single shared audio rendition, or nothing at all.
     *
     * <p>{@code -b:a} is emitted only for an encode. A copied track has no target bitrate, and the
     * previous shape — an audio bitrate carried per rung, zeroed on a copied rung so the bandwidth
     * arithmetic would not double-count — put {@code -b:a:0 0k} on the command line for every source
     * whose audio had to be re-encoded beneath a copied video stream.
     */
    private static List<String> audioArgs(AudioPlan audio) {
        if (!audio.present()) {
            return List.of("-an");
        }
        List<String> args = new ArrayList<>(List.of("-map", "a:" + audio.sourceIndex()));
        if (audio.isCopy()) {
            args.addAll(List.of("-c:a:0", "copy"));
            return args;
        }
        args.addAll(List.of(
                "-c:a:0", audio.encoder(),
                "-b:a:0", kbps(audio.bitrateKbps()),
                "-ac:a:0", String.valueOf(audio.channels()),
                "-ar:a:0", String.valueOf(audio.sampleRate())));
        return args;
    }

    private String keyFrameExpression() {
        return "expr:gte(t,n_forced*%d)".formatted(segmentDuration.toSeconds());
    }

    /**
     * Rewrites only the shared audio rendition, leaving every video rung alone.
     *
     * <p>For the repair that follows a host gaining a decoder it did not have. The video segments
     * are already correct — re-encoding them would spend minutes producing identical bytes — but
     * the audio was copied through untouched because nothing here could decode it, and now
     * something can.
     *
     * <p>Filenames are literal rather than templated, and that is not a style choice. ffmpeg
     * substitutes {@code %v} in {@code -hls_fmp4_init_filename} only when {@code -var_stream_map}
     * declares two or more variants; with one, it writes a file called {@code %v_init.mp4} and
     * points the playlist at it. Verified, because it is the kind of thing that produces a ladder
     * that looks right in a listing and 404s on the first segment.
     */
    public List<String> buildAudioOnly(Path inputFile, Path outputDirectory, AudioPlan audio) {
        List<String> command = new ArrayList<>(List.of(
                binary, "-hide_banner", "-nostdin", "-y", "-nostats", "-progress", "pipe:1",
                "-i", inputFile.toString()));

        command.addAll(audioArgs(audio));
        // Nothing but the audio. Without -vn the muxer would take the video too and overwrite the
        // rungs this exists to preserve.
        command.addAll(List.of("-vn", "-sn", "-dn"));

        String name = AudioPlan.RENDITION_NAME;
        command.addAll(List.of(
                "-f", "hls",
                "-hls_time", String.valueOf(segmentDuration.toSeconds()),
                "-hls_playlist_type", "vod",
                "-hls_flags", "independent_segments",
                "-hls_segment_type", "fmp4",
                "-hls_fmp4_init_filename", name + "_init.mp4",
                "-hls_segment_filename", outputDirectory.resolve(name + "_%03d.m4s").toString(),
                "-var_stream_map", "a:0,name:" + name,
                outputDirectory.resolve(name + ".m3u8").toString()));
        return List.copyOf(command);
    }

    /**
     * Extracts one text subtitle track as WebVTT.
     *
     * <p>Its own invocation rather than an {@code sgroup:} entry in the ladder's
     * {@code -var_stream_map}. That key exists and works, but it makes one hlsenc instance emit fMP4
     * video segments and WebVTT text segments simultaneously, which is the least exercised corner of
     * that muxer — and the ladder is the command that must not become fragile. This reads a text
     * stream measured in kilobytes and touches nothing else.
     */
    public List<String> buildSubtitle(Path inputFile, Path outputFile, SubtitlePlan subtitle) {
        return List.of(
                binary,
                "-hide_banner",
                "-nostdin",
                "-y",
                "-v", "error",
                "-i", inputFile.toString(),
                "-map", "0:s:" + subtitle.sourceIndex(),
                "-c:s", "webvtt",
                "-f", "webvtt",
                outputFile.toString());
    }

    /**
     * A single still frame, for the library to show as a thumbnail.
     *
     * <p>Uses the {@code thumbnail} filter rather than taking whatever frame the seek lands on: it
     * scores a batch of frames and picks the most representative, which is what keeps posters from
     * being the black frame or fade-in that so many files open on.
     *
     * @param seek where to start reading, or null to start at the beginning
     */
    public List<String> buildPoster(Path inputFile, Path outputFile, Duration seek) {
        List<String> command = new ArrayList<>(List.of(binary, "-hide_banner", "-nostdin", "-y"));
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
     * A scaling chain: each rung is scaled from the one above it rather than from the source.
     *
     * <p>{@code [0:v]scale=1280:720,split=2[v0out][c0];[c0]scale=854:480,split=2[v1out][c1];…}
     *
     * <p>The previous shape split the source four ways and ran four independent scalers, each
     * reading full 1080p frames. Deriving each rung from the one above measures 21% less CPU across
     * the whole ladder on the machine this was written on, because only the first scaler ever
     * touches a full-size frame. The quality it costs is real and negligible: the bottom rung, which
     * is four resampling steps from the source and therefore the worst case, measures SSIM 0.998 and
     * PSNR 47 dB against a direct downscale — far below what the H.264 encode at that rung's bitrate
     * takes out on its own.
     *
     * <p>Rungs arrive largest-first, which is what makes the chain a chain rather than a series of
     * upscales.
     */
    private static String filterGraph(List<PlannedRendition> encodedRungs) {
        StringBuilder graph = new StringBuilder();
        String source = "[0:v]";
        for (int i = 0; i < encodedRungs.size(); i++) {
            HlsRendition rung = encodedRungs.get(i).rendition();
            boolean last = i == encodedRungs.size() - 1;
            if (i > 0) {
                graph.append(';');
            }
            graph.append(source).append("scale=w=%d:h=%d".formatted(rung.width(), rung.height()));
            if (last) {
                graph.append("[v%dout]".formatted(i));
            } else {
                graph.append(",split=2[v%dout][chain%d]".formatted(i, i));
                source = "[chain%d]".formatted(i);
            }
        }
        return graph.toString();
    }

    /**
     * {@code v:0,agroup:aud,name:1080p … a:0,agroup:aud,name:audio,default:yes}.
     *
     * <p>The {@code name:} is what keeps files flat; the {@code agroup:} is what makes the audio its
     * own variant that every video rung references rather than a copy muxed into each of them.
     */
    private static String variantStreamMap(List<PlannedRendition> ladder, AudioPlan audio) {
        String video = IntStream.range(0, ladder.size())
                .mapToObj(i -> audio.present()
                        ? "v:%d,agroup:%s,name:%s".formatted(i, AudioPlan.GROUP_ID, ladder.get(i).rendition().name())
                        : "v:%d,name:%s".formatted(i, ladder.get(i).rendition().name()))
                .collect(Collectors.joining(" "));
        if (!audio.present()) {
            return video;
        }
        return video
                + " a:0,agroup:%s,name:%s,default:yes".formatted(AudioPlan.GROUP_ID, AudioPlan.RENDITION_NAME);
    }

    private static String kbps(int value) {
        return value + "k";
    }
}
