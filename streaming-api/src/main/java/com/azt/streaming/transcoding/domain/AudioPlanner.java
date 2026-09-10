package com.azt.streaming.transcoding.domain;

import java.util.Optional;

/**
 * Decides what to do with the source's audio, before ffmpeg is started.
 *
 * <p>This exists because of one log line:
 *
 * <pre>{@code [aist#0:1/eac3] Decoding requested, but no decoder found for: eac3}</pre>
 *
 * <p>which arrived after a 756 MB download, as the tail of a forty-line stack trace, from a file
 * that was in no way unusual — E-AC-3 is the audio of essentially every AMZN WEB-DL, and a
 * patent-free ffmpeg build carries no decoder for it. The pipeline had asked the encoder to decode
 * something and found out it could not at the point of failure rather than the point of decision.
 *
 * <p>Now the decision is made from the probe and the build's own capability listing, so it costs
 * milliseconds and produces a sentence instead of a stack trace.
 *
 * <p>A pure function, like {@link LadderPlanner}, so the whole table can be asserted without a
 * video file or a subprocess.
 */
public final class AudioPlanner {

    private AudioPlanner() {}

    /**
     * The audio rendition worth building for this source.
     *
     * @param source what ffprobe found
     * @param preferences what the operator asked for
     * @param support what this ffmpeg build can actually do
     * @throws TranscodingException when the audio cannot be delivered as AAC and the configured
     *     policy is {@link UndecodableAudioPolicy#FAIL}
     */
    public static AudioPlan plan(ProbedSource source, AudioPreferences preferences, FfmpegSupport support) {
        Optional<ProbedAudio> primary = source.primaryAudio();
        if (primary.isEmpty()) {
            return AudioPlan.none("the source carries no audio track");
        }
        ProbedAudio track = primary.get();

        // Already exactly what this would produce. Copying costs nothing, needs no decoder, and
        // loses no generation of quality — the one outcome better than a good encode.
        //
        // The test used to be "AAC-LC with at most two channels", which re-encoded every 5.1
        // AAC-LC source purely to fold it to stereo. Now that the layout is preserved, the
        // question is simply whether encoding would change anything.
        if (track.isPlainAac() && preferences.wouldLeaveUnchanged(track)) {
            return AudioPlan.copy(track, null);
        }

        Optional<String> encoder = support.firstEncoder(preferences.encoderPreference());
        boolean decodable = support.canDecode(track.codec());
        if (decodable && encoder.isPresent()) {
            return AudioPlan.encode(track, encoder.get(), preferences);
        }

        String obstacle = !decodable
                ? "this ffmpeg build has no %s decoder".formatted(track.codec())
                : "this ffmpeg build has no AAC encoder";

        if (preferences.onUndecodable() == UndecodableAudioPolicy.FAIL) {
            throw new TranscodingException(obstacle
                    + ", and aztcast.streaming.ffmpeg.audio.on-undecodable is set to fail."
                    + " Install a build that carries it, or set the policy to passthrough or drop.");
        }

        if (preferences.onUndecodable() == UndecodableAudioPolicy.PASSTHROUGH && track.isPackageable()) {
            // The escape hatch: fMP4 can carry the track verbatim, so no decoder is needed to
            // deliver it. Not every player can play it — which is precisely why the plan carries
            // the real CODECS identifier rather than claiming AAC.
            return AudioPlan.copy(
                    track,
                    obstacle + "; copying the %s track into the segments untouched"
                            .formatted(track.codec()));
        }

        return AudioPlan.none(obstacle + "; encoding video only");
    }
}
