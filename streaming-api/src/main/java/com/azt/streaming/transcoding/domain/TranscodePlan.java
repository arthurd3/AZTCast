package com.azt.streaming.transcoding.domain;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Everything decided about one source before ffmpeg is started.
 *
 * <p>Assembling it in one place is what turned a class of runtime failure into a log line. The
 * pipeline used to discover mid-encode that it could not decode the audio it had been handed; now
 * every such question is answered against the probe and the build's capability listing, and what
 * reaches ffmpeg is a plan that the binary is known to be able to execute.
 *
 * @param renditions the video rungs, best first
 * @param audio the single audio rendition every rung shares
 * @param subtitles text subtitle tracks to republish as WebVTT
 * @param framesPerSegment GOP length in frames, or 0 when the frame rate was not reported
 */
public record TranscodePlan(
        List<PlannedRendition> renditions, AudioPlan audio, List<SubtitlePlan> subtitles, int framesPerSegment) {

    public TranscodePlan {
        renditions = List.copyOf(renditions);
        subtitles = List.copyOf(subtitles);
    }

    /** The plan as one log line, e.g. {@code 1080p(copy) 720p 480p + audio(copy eac3) + 4 subtitles}. */
    public String describe() {
        String rungs = renditions.stream()
                .map(rung -> rung.copyVideo() ? rung.rendition().name() + "(copy)" : rung.rendition().name())
                .collect(Collectors.joining(" "));
        StringBuilder description = new StringBuilder(rungs);
        description.append(switch (audio.mode()) {
            case NONE -> " + no audio";
            case COPY -> " + audio(copy)";
            case ENCODE -> " + audio(%s %dk)".formatted(audio.encoder(), audio.bitrateKbps());
        });
        if (!subtitles.isEmpty()) {
            description.append(" + %d subtitle track(s)".formatted(subtitles.size()));
        }
        return description.toString();
    }
}
