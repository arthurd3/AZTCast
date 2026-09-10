package com.azt.streaming.transcoding.domain;

/**
 * One video rung of the HLS ladder, with the encoder and playlist arithmetic that belongs to it.
 *
 * <p>This replaces three index-aligned {@code String[]} — {@code {"720","240"}},
 * {@code {"1280x720","426x240"}} and {@code {"3000k","800k"}} — walked by a shared loop counter, so
 * adding a rung meant editing three arrays in lockstep and any mismatch was silent.
 *
 * <p>It carried an audio bitrate until the ladder moved to a single shared audio rendition. Holding
 * one per rung was what made {@code -b:a:0 0k} reachable: the copied top rung sets its audio bitrate
 * to zero so the bandwidth arithmetic would not count the container's audio twice, and that zero
 * became an ffmpeg argument on every source whose audio had to be re-encoded. There is one audio
 * bitrate now, it lives on {@link AudioPlan}, and the master playlist adds it once.
 *
 * @param name rung label, also the playlist basename (e.g. {@code 720p})
 * @param width scaled output width in pixels
 * @param height scaled output height in pixels
 * @param videoBitrateKbps target video bitrate
 */
public record HlsRendition(String name, int width, int height, int videoBitrateKbps) {

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

    /** Peak video bits per second, before the shared audio rendition is added to it. */
    public int peakVideoBps() {
        return maxrateKbps() * 1000;
    }

    /** Average video bits per second, before the shared audio rendition is added to it. */
    public int averageVideoBps() {
        return videoBitrateKbps * 1000;
    }
}
