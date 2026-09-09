package com.azt.streaming.transcoding.domain;

/**
 * One rung of the HLS ladder, with the encoder and playlist arithmetic that belongs to it.
 *
 * <p>This replaces three index-aligned {@code String[]} — {@code {"720","240"}},
 * {@code {"1280x720","426x240"}} and {@code {"3000k","800k"}} — walked by a shared loop counter, so
 * adding a rung meant editing three arrays in lockstep and any mismatch was silent.
 *
 * @param name rung label, also the playlist basename (e.g. {@code 720p})
 * @param width scaled output width in pixels
 * @param height scaled output height in pixels
 * @param videoBitrateKbps target video bitrate
 * @param audioBitrateKbps target audio bitrate
 */
public record HlsRendition(
        String name, int width, int height, int videoBitrateKbps, int audioBitrateKbps) {

    /** Headroom above the target bitrate the encoder may use, ~7%. */
    private static final double MAXRATE_FACTOR = 1.07;

    /** Rate-control buffer, 1.5x the target bitrate. */
    private static final double BUFSIZE_FACTOR = 1.5;

    public String resolution() {
        return width + "x" + height;
    }

    public int maxrateKbps() {
        return (int) (videoBitrateKbps * MAXRATE_FACTOR);
    }

    public int bufsizeKbps() {
        return (int) (videoBitrateKbps * BUFSIZE_FACTOR);
    }

    /** Playlist file for this rung, relative to the video folder. */
    public String playlistFileName() {
        return name + ".m3u8";
    }

    /**
     * ffmpeg segment filename pattern for this rung, relative to the video folder.
     *
     * <p>In the command itself this is templated as {@code %v_%03d.m4s} and ffmpeg substitutes
     * {@code %v} from the {@code name:} key of {@code -var_stream_map}; the result is exactly this
     * string. Kept here because it is the name playback will be asked for.
     */
    public String segmentPattern() {
        return name + "_%03d.m4s";
    }

    /**
     * The CMAF initialisation segment for this rung.
     *
     * <p>fMP4 splits what a transport stream repeated in every packet into one header segment plus
     * the media segments. A variant playlist points at it with {@code EXT-X-MAP}, and without it the
     * rung is undecodable — so this file is served by the same frozen mapping as everything else,
     * which is why it too has to be a single path segment.
     */
    public String initFileName() {
        return name + "_init.mp4";
    }

    /**
     * Peak bits per second, for {@code #EXT-X-STREAM-INF:BANDWIDTH}.
     *
     * <p>The old value was the video bitrate alone, produced by rewriting {@code "3000k"} to
     * {@code "3000000"}. That both ignored the 128 kbps audio track and reported the target rather
     * than the peak, so players underestimated what the stream costs and could pick a rung the
     * connection cannot sustain.
     */
    public int peakBandwidthBps() {
        return (maxrateKbps() + audioBitrateKbps) * 1000;
    }

    /** Average bits per second, for {@code AVERAGE-BANDWIDTH}. */
    public int averageBandwidthBps() {
        return (videoBitrateKbps + audioBitrateKbps) * 1000;
    }

}
