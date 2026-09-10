package com.azt.streaming.transcoding.domain;

import java.util.List;

/**
 * What the operator wants from the audio rendition, before the source has any say in it.
 *
 * @param encoderPreference AAC encoders in descending order of preference. {@code libfdk_aac} beats
 *     the native encoder at the bitrates this ladder uses, and plenty of builds carry it — but
 *     plenty do not, which is the entire reason this is a list and not a name.
 * @param bitrateKbps target for the single shared rendition
 * @param channels channels to downmix to
 * @param sampleRate output sample rate
 * @param onUndecodable what to do when the source audio cannot be turned into AAC here
 */
public record AudioPreferences(
        List<String> encoderPreference,
        int bitrateKbps,
        int channels,
        int sampleRate,
        UndecodableAudioPolicy onUndecodable) {

    public AudioPreferences {
        encoderPreference = List.copyOf(encoderPreference);
    }
}
