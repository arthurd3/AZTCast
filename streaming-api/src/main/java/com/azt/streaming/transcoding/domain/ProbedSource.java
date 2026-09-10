package com.azt.streaming.transcoding.domain;

import java.util.List;
import java.util.Optional;

/**
 * Everything ffprobe found in a file this service is about to transcode.
 *
 * <p>Separate from {@link ProbedVideo} because they answer different questions and the difference
 * used to be carried by convention. {@code ProbedVideo} measures a rung <em>this service produced</em>
 * so a CODECS string can be written for it; nothing else about it is interesting, which is why its
 * factory leaves the dimensions at zero. This type describes an arbitrary file that arrived from a
 * swarm, and every field is a decision: what can be copied, how tall a ladder is worth building, how
 * many channels to downmix, which subtitle tracks exist, where to seek for a poster.
 *
 * @param hasVideo whether a video stream is present at all
 * @param videoCodec {@code codec_name} of the first video stream, e.g. {@code h264}
 * @param videoProfile {@code profile}, e.g. {@code High}
 * @param videoLevel {@code level_idc}, e.g. {@code 40} for level 4.0
 * @param width source width in pixels, 0 when unmeasured
 * @param height source height in pixels, 0 when unmeasured
 * @param frameRate frames per second from {@code r_frame_rate}, 0 when unreported
 * @param bitRateKbps container bitrate, 0 when the file declares none and none can be derived
 * @param durationSeconds container duration, 0 when unreported
 * @param audioTracks every audio stream, in container order
 * @param subtitleTracks every subtitle stream, in container order
 */
public record ProbedSource(
        boolean hasVideo,
        String videoCodec,
        String videoProfile,
        int videoLevel,
        int width,
        int height,
        double frameRate,
        int bitRateKbps,
        double durationSeconds,
        List<ProbedAudio> audioTracks,
        List<ProbedSubtitle> subtitleTracks) {

    public ProbedSource {
        audioTracks = List.copyOf(audioTracks);
        subtitleTracks = List.copyOf(subtitleTracks);
    }

    public boolean hasAudio() {
        return !audioTracks.isEmpty();
    }

    /**
     * The track a viewer should hear.
     *
     * <p>The container's default flag, and only the first stream as a fallback. Taking {@code a:0}
     * unconditionally is wrong on any release that ships a commentary track ahead of the feature
     * audio, and there is no way to notice from the output that it happened.
     */
    public Optional<ProbedAudio> primaryAudio() {
        return audioTracks.stream().filter(ProbedAudio::isDefault).findFirst().or(() -> audioTracks.stream().findFirst());
    }

    /** Subtitle tracks that can become WebVTT. Bitmap formats are silently not among them. */
    public List<ProbedSubtitle> textSubtitles() {
        return subtitleTracks.stream().filter(ProbedSubtitle::isText).toList();
    }

    /**
     * Whether the video stream can be put into an HLS segment without re-encoding.
     *
     * <p>H.264 only. HLS carries HEVC and AV1 too, but the master playlist this service writes, the
     * CODECS strings it derives and the players it is tested against are all built around AVC, and
     * copying an HEVC stream into the top rung would produce a ladder whose best rung many browsers
     * silently refuse.
     */
    public boolean videoIsCopyable() {
        return hasVideo && "h264".equalsIgnoreCase(videoCodec) && width > 0 && height > 0;
    }

    /**
     * Frames per segment at the configured segment length, for {@code -g} and {@code -keyint_min}.
     *
     * <p>Zero when the frame rate is unknown, which the command builder reads as "emit neither".
     * A GOP length guessed from a frame rate that was never reported is worse than none: it would
     * put keyframes at the wrong interval on every variable-frame-rate source, and
     * {@code -force_key_frames} — which works in seconds and needs no frame rate — already
     * guarantees the segment boundary either way.
     */
    public int framesPerSegment(long segmentSeconds) {
        return frameRate <= 0 ? 0 : (int) Math.round(frameRate * segmentSeconds);
    }
}
