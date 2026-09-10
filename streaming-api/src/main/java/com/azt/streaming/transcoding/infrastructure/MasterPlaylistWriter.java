package com.azt.streaming.transcoding.infrastructure;

import com.azt.streaming.shared.storage.MediaStorage;
import com.azt.streaming.transcoding.domain.AudioPlan;
import com.azt.streaming.transcoding.domain.EncodedAudio;
import com.azt.streaming.transcoding.domain.EncodedRendition;
import com.azt.streaming.transcoding.domain.HlsRendition;
import com.azt.streaming.transcoding.domain.SubtitlePlan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Writes the HLS master playlist that advertises the ladder to the player.
 *
 * <p>Written here rather than by ffmpeg's {@code -master_pl_name} for two reasons. It has to be the
 * <em>last</em> file to appear, because its presence is this service's "ladder is ready" sentinel
 * and ffmpeg writes its master when the encode starts. And it has to name codecs measured off the
 * finished rungs, and join each video variant to an audio group whose own codec depends on a
 * decision made from the source — neither of which ffmpeg's writer can express.
 */
@Component
public class MasterPlaylistWriter {

    /**
     * Version 7 because the variants are fMP4: {@code EXT-X-MAP} needs 6, and 7 is what ffmpeg
     * declares in the variants it writes. A master claiming 3 while pointing at version-7 variants
     * is inconsistent even where players tolerate it.
     */
    private static final int PLAYLIST_VERSION = 7;

    /**
     * Renders the master playlist.
     *
     * <p>Variant URIs stay relative: hls.js resolves them against the master URL, so the API can be
     * mounted anywhere without rewriting playlists.
     */
    public String render(List<EncodedRendition> renditions, EncodedAudio audio, List<SubtitlePlan> subtitles) {
        StringBuilder playlist = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:")
                .append(PLAYLIST_VERSION)
                .append('\n')
                // Stated once at master level rather than left to each variant: it tells the player
                // every segment is independently decodable, which is what permits switching rungs.
                .append("#EXT-X-INDEPENDENT-SEGMENTS\n");

        if (audio.present()) {
            appendAudioRendition(playlist, audio);
        }
        subtitles.forEach(subtitle -> appendSubtitleRendition(playlist, subtitle));

        boolean hasSubtitles = !subtitles.isEmpty();
        for (EncodedRendition encoded : renditions) {
            appendVariant(playlist, encoded, audio, hasSubtitles, true);
        }

        // The same rungs again, without the audio group, when the audio is one only Apple's
        // platforms decode. A player keeps whichever family its CODECS attribute says it can
        // assemble; a browser that cannot decode E-AC-3 keeps these and plays the video, where
        // before it was left with no variant at all and refused the stream outright.
        //
        // The same media playlists, referenced a second way. Not one byte more on disk.
        if (audio.present() && !audio.plan().isWidelyPlayable()) {
            for (EncodedRendition encoded : renditions) {
                appendVariant(playlist, encoded, audio, hasSubtitles, false);
            }
        }
        return playlist.toString();
    }

    /**
     * Writes a playlist that has already been rendered and checked.
     *
     * <p>Separate from {@link #render} so the integrity check inspects the exact bytes that will
     * land on disk. Rendering twice — once to verify, once to write — would let a change between
     * the two go unnoticed, which is precisely the class of thing the check exists to catch.
     */
    public void write(Path videoDirectory, String rendered) throws IOException {
        Files.writeString(
                videoDirectory.resolve(MediaStorage.MASTER_PLAYLIST), rendered, StandardCharsets.UTF_8);
    }

    /**
     * The one audio rendition every variant points at.
     *
     * <p>{@code CHANNELS} is required of an audio rendition by Apple's authoring specification and
     * is the only place a player can learn that a copied 5.1 track is 5.1 before it opens it.
     */
    private static void appendAudioRendition(StringBuilder playlist, EncodedAudio audio) {
        AudioPlan plan = audio.plan();
        playlist.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"")
                .append(AudioPlan.GROUP_ID)
                .append("\",NAME=\"")
                .append(escape(plan.label()))
                .append("\",LANGUAGE=\"")
                .append(plan.language())
                .append('"');
        if (plan.channels() > 0) {
            playlist.append(",CHANNELS=\"").append(plan.channels()).append('"');
        }
        playlist.append(",DEFAULT=YES,AUTOSELECT=YES,URI=\"")
                .append(plan.playlistFileName())
                .append("\"\n");
    }

    /**
     * One subtitle track.
     *
     * <p>{@code DEFAULT=NO} on every track, deliberately: a player that honours DEFAULT turns
     * subtitles on without being asked, and burning captions onto a viewer who did not ask for them
     * is the kind of helpfulness people uninstall software over. {@code AUTOSELECT=YES} on every
     * track is the other half of that — it does not turn anything on, it says the player may pick
     * this one when the viewer's own language or accessibility preferences match it, which is
     * exactly the behaviour worth having.
     *
     * <p>{@code CHARACTERISTICS} is omitted rather than emitted empty. It is a list of media
     * characteristic tags; an empty list is not one, and writing {@code CHARACTERISTICS=""} on
     * three of four tracks says nothing while looking like it says something.
     */
    private static void appendSubtitleRendition(StringBuilder playlist, SubtitlePlan subtitle) {
        playlist.append("#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"")
                .append(SubtitlePlan.GROUP_ID)
                .append("\",NAME=\"")
                .append(escape(subtitle.label()))
                .append("\",LANGUAGE=\"")
                .append(subtitle.language())
                .append("\",DEFAULT=NO,AUTOSELECT=YES,FORCED=")
                .append(subtitle.forced() ? "YES" : "NO");
        if (subtitle.hearingImpaired()) {
            playlist.append(",CHARACTERISTICS=\"public.accessibility.describes-music-and-sound\"");
        }
        playlist.append(",URI=\"").append(subtitle.playlistFileName()).append("\"\n");
    }

    /**
     * One video variant.
     *
     * <p>{@code CODECS} names the combination the player will assemble — the video measured off this
     * rung plus the audio group it is being pointed at — because that is what the attribute means.
     * With the audio demuxed into its own rendition, the rung's own probe reports video only, so
     * leaving it at that would advertise a silent stream for something that has sound.
     *
     * <p>And it is why the attribute is dangerous. A codec the player cannot decode does not cost
     * it that stream; it disqualifies the whole variant. That is the exact mechanism by which a
     * ladder of five perfectly good H.264 rungs became unplayable in Chrome.
     *
     * @param withAudio whether this entry joins the audio group. False emits the same rung as a
     *     video-only offering, for a player that cannot decode what is in the group.
     */
    private static void appendVariant(
            StringBuilder playlist,
            EncodedRendition encoded,
            EncodedAudio audio,
            boolean hasSubtitles,
            boolean withAudio) {
        HlsRendition rendition = encoded.rendition();
        boolean joined = withAudio && audio.present();
        playlist
                .append("#EXT-X-STREAM-INF:BANDWIDTH=")
                .append(encoded.peakVideoBps() + (joined ? audio.peakBps() : 0))
                .append(",AVERAGE-BANDWIDTH=")
                .append(encoded.averageVideoBps() + (joined ? audio.averageBps() : 0))
                .append(",RESOLUTION=")
                .append(rendition.resolution())
                .append(",CODECS=\"")
                .append(joined ? encoded.codecs() + "," + audio.plan().codecs() : encoded.codecs())
                .append('"');
        if (joined) {
            playlist.append(",AUDIO=\"").append(AudioPlan.GROUP_ID).append('"');
        }
        if (hasSubtitles) {
            // Kept on both families. Subtitles are their own group and their own segments; nothing
            // about them depends on which audio the player ended up with.
            playlist.append(",SUBTITLES=\"").append(SubtitlePlan.GROUP_ID).append('"');
        }
        playlist.append('\n').append(rendition.playlistFileName()).append('\n');
    }

    /**
     * Quoted-string attribute values may not contain a double quote or a newline.
     *
     * <p>The values here come from container tags — a title an arbitrary release group typed — so
     * they are exactly the kind of input that eventually contains one.
     */
    private static String escape(String value) {
        return value == null ? "" : value.replace("\"", "").replace("\n", " ").replace("\r", " ");
    }
}
