package com.azt.streaming.transcoding.domain;

import java.util.List;

/**
 * What the operator wants from the audio rendition, resolved against what the source turned out to
 * be.
 *
 * <p>Every one of these used to be a fixed number, and the fixed numbers were wrong. A 128 kbps
 * stereo target applied to a 5.1 track at 640 kbps discarded four channels and four fifths of the
 * bitrate — for no gain in reach, because six-channel AAC is decoded by every mainstream browser
 * ({@code navigator.mediaCapabilities} reports it {@code supported} and {@code smooth}) and folded
 * down by the viewer's own output device, which knows their speakers.
 *
 * @param encoderPreference AAC encoders in descending order of preference. {@code libfdk_aac} beats
 *     the native encoder at these bitrates and plenty of builds carry it — but plenty do not, and
 *     this project's own Docker image is one of them, which is why this is a list and not a name.
 * @param channels the layout to encode at, or 0 to keep the source's
 * @param sampleRate the rate to encode at, or 0 to keep the source's
 * @param bitrateKbpsPerChannel scales the target with the layout, so one setting is right for both
 *     a stereo and a 5.1 source
 * @param maxBitrateKbps ceiling, so an exotic layout cannot ask for an absurd rate
 * @param onUndecodable what to do when the source audio cannot be turned into AAC here
 */
public record AudioPreferences(
        List<String> encoderPreference,
        int channels,
        int sampleRate,
        int bitrateKbpsPerChannel,
        int maxBitrateKbps,
        UndecodableAudioPolicy onUndecodable) {

    /**
     * The most channels AAC carries.
     *
     * <p>A source above this has to be folded down whatever the configuration says, and saying so
     * here is better than handing ffmpeg a layout its encoder will refuse.
     */
    public static final int MAX_AAC_CHANNELS = 8;

    public AudioPreferences {
        encoderPreference = List.copyOf(encoderPreference);
    }

    /** The layout this track will be encoded at. */
    public int channelsFor(ProbedAudio track) {
        int wanted = channels > 0 ? channels : track.channels();
        // An unreported channel count is the one case with nothing to preserve. Stereo is the
        // safe assumption: it is what the overwhelming majority of such sources turn out to be,
        // and asking for more channels than exist produces silence in the invented ones.
        int resolved = wanted > 0 ? wanted : 2;
        return Math.min(resolved, MAX_AAC_CHANNELS);
    }

    /** The rate this track will be encoded at. */
    public int sampleRateFor(ProbedAudio track) {
        int wanted = sampleRate > 0 ? sampleRate : track.sampleRate();
        return wanted > 0 ? wanted : 48000;
    }

    /** The target bitrate for a given layout, capped. */
    public int bitrateFor(int channelCount) {
        return Math.min(bitrateKbpsPerChannel * channelCount, maxBitrateKbps);
    }

    /**
     * Whether encoding this track would change it at all.
     *
     * <p>When it would not — the source is already what this would produce — copying is both the
     * highest quality available and free. Re-encoding it only spends CPU to lose a generation.
     */
    public boolean wouldLeaveUnchanged(ProbedAudio track) {
        return channelsFor(track) == track.channels() && sampleRateFor(track) == track.sampleRate();
    }
}
