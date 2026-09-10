package com.azt.streaming.transcoding.domain;

import java.util.Locale;
import java.util.Set;

/**
 * The one audio rendition every rung will share, and how ffmpeg should produce it.
 *
 * <p>One, not one per rung. The ladder used to map {@code a:0} five times and encode the same track
 * to AAC at five bitrates, which cost five encodes and wrote five copies of the same audio into the
 * segments — roughly 90 MB of duplicated audio on a 22-minute episode. Apple's authoring
 * specification asks for the opposite: a single audio group that every variant references.
 *
 * <p>Collapsing it also removed the defect that started this. With one audio stream there is one
 * audio bitrate, so the per-rung {@code audioBitrateKbps} — which {@code LadderPlanner} deliberately
 * sets to zero on a copied rung, and which leaked out as {@code -b:a:0 0k} whenever that rung's audio
 * had to be re-encoded — is no longer an ffmpeg argument at all.
 *
 * @param mode whether to encode the track, copy it, or produce no audio
 * @param sourceIndex the {@code N} in {@code -map a:N}; meaningless when {@code mode} is NONE
 * @param encoder the resolved encoder name for ENCODE, null otherwise
 * @param bitrateKbps target bitrate for ENCODE, 0 for COPY — a copied track has no target
 * @param channels channels the rendition will carry, for the playlist's {@code CHANNELS} attribute
 * @param sampleRate output sample rate for ENCODE, 0 for COPY
 * @param language RFC 5646 tag for the playlist's {@code LANGUAGE} attribute
 * @param label what a track picker should show
 * @param codecs the RFC 6381 identifier the master playlist must advertise for this track
 * @param reason a sentence for the log explaining a decision that was not the obvious one, or null
 */
public record AudioPlan(
        Mode mode,
        int sourceIndex,
        String encoder,
        int bitrateKbps,
        int channels,
        int sampleRate,
        String language,
        String label,
        String codecs,
        String reason) {

    public enum Mode {
        /** No audio rendition at all. The ladder is video-only and the master advertises no audio. */
        NONE,
        /** Remux the source track. Needs no decoder, which is what makes it the fallback it is. */
        COPY,
        /** Decode and re-encode to AAC. */
        ENCODE
    }

    /** The name of the shared rendition, and therefore of its playlist, init segment and segments. */
    public static final String RENDITION_NAME = "audio";

    /** The {@code GROUP-ID} every variant points at with {@code AUDIO="…"}. */
    public static final String GROUP_ID = "aud";

    /**
     * Audio codecs a mainstream browser will accept through Media Source Extensions.
     *
     * <p>The list that is missing from here is the point: {@code ec-3}, {@code ac-3} and
     * {@code alac} play on Apple's platforms and nowhere else. That matters far more than it
     * sounds, because a variant's {@code CODECS} attribute describes the whole combination — so an
     * audio codec the browser lacks does not cost the viewer the audio, it disqualifies the entire
     * variant. Chrome answers {@code false} to
     * {@code isTypeSupported('video/mp4; codecs="avc1.640028,ec-3"')} even though it answers
     * {@code true} to the video half alone, and hls.js then filters away every rung and reports
     * {@code manifestIncompatibleCodecsError}. The video does not play at all.
     */
    private static final Set<String> WIDELY_PLAYABLE =
            Set.of("mp4a.40.2", "mp4a.40.5", "mp4a.40.29", "mp4a.40.34", "opus", "flac");

    public boolean present() {
        return mode != Mode.NONE;
    }

    public boolean isCopy() {
        return mode == Mode.COPY;
    }

    /**
     * Whether a browser that is not Safari can be expected to decode this rendition.
     *
     * <p>False here is what makes {@code MasterPlaylistWriter} advertise every rung a second time
     * without an audio group, so that the video remains playable on a machine that cannot decode
     * the audio travelling beside it.
     */
    public boolean isWidelyPlayable() {
        return !present() || isWidelyPlayableCodec(codecs);
    }

    /** Whether one RFC 6381 audio identifier is decodable outside Apple's platforms. */
    public static boolean isWidelyPlayableCodec(String codec) {
        return codec != null && WIDELY_PLAYABLE.contains(codec.toLowerCase(Locale.ROOT));
    }

    public String playlistFileName() {
        return RENDITION_NAME + ".m3u8";
    }

    public static AudioPlan none(String reason) {
        return new AudioPlan(Mode.NONE, -1, null, 0, 0, 0, null, null, null, reason);
    }

    public static AudioPlan copy(ProbedAudio track, String reason) {
        return new AudioPlan(
                Mode.COPY,
                track.index(),
                null,
                0,
                track.channels(),
                0,
                LanguageTag.bcp47(track.language()),
                labelFor(track),
                track.codecsWhenCopied(),
                reason);
    }

    public static AudioPlan encode(ProbedAudio track, String encoder, AudioPreferences preferences) {
        return new AudioPlan(
                Mode.ENCODE,
                track.index(),
                encoder,
                preferences.bitrateKbps(),
                preferences.channels(),
                preferences.sampleRate(),
                LanguageTag.bcp47(track.language()),
                labelFor(track),
                // Every encoder in the preference list produces AAC-LC, so this is knowable without
                // measuring. If a non-AAC encoder is ever configured here, this has to be measured
                // from the output the way the video half already is.
                ProbedVideo.AAC_LC,
                null);
    }

    private static String labelFor(ProbedAudio track) {
        String base = LanguageTag.displayName(track.language());
        if (track.title() == null || track.title().isBlank()) {
            return base;
        }
        return base + " (" + track.title().trim() + ")";
    }
}
