package com.azt.streaming.support;

import com.azt.streaming.shared.config.StreamingProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Builds {@link StreamingProperties} for tests that need one without a Spring context.
 *
 * <p>Every field a test has ever wanted to vary has a builder method, including the ffmpeg binaries
 * and timeout — {@code RealFfmpegLadderTest} used to reconstruct the whole {@code Ffmpeg} record by
 * hand because those three were the ones this fixture did not expose.
 */
public final class PropertiesFixture {

    public static final StreamingProperties.Rendition RENDITION_720P =
            new StreamingProperties.Rendition("720p", 1280, 720, 3000);

    public static final StreamingProperties.Rendition RENDITION_240P =
            new StreamingProperties.Rendition("240p", 426, 240, 800);

    private PropertiesFixture() {}

    public static Builder defaults() {
        return new Builder();
    }

    public static final class Builder {
        private Path downloadsDir = Path.of("target/test/downloads");
        private Path hlsDir = Path.of("target/test/hls");
        private String binary = "/bin/true";
        private String probeBinary = "/bin/true";
        private String videoCodec = "libx264";
        private String preset = "auto";
        private Duration ffmpegTimeout = Duration.ofSeconds(30);
        private Duration segmentDuration = Duration.ofSeconds(4);
        private StreamingProperties.UndecodableAudioPolicy onUndecodableAudio =
                StreamingProperties.UndecodableAudioPolicy.PASSTHROUGH;
        private boolean subtitlesEnabled = true;
        private List<StreamingProperties.Rendition> renditions = List.of(RENDITION_720P, RENDITION_240P);
        private List<String> videoExtensions = List.of("mp4", "mkv");
        private List<String> extraTrackers = List.of();
        private List<String> deadTrackers = List.of();
        private boolean downloadVideoOnly = true;
        private boolean offloadEnabled = false;
        private String internalPrefix = "/_media";
        private boolean redisEnabled = false;
        private int rateLimitCapacity = 5;

        public Builder hlsDir(Path value) {
            this.hlsDir = value;
            return this;
        }

        public Builder downloadsDir(Path value) {
            this.downloadsDir = value;
            return this;
        }

        public Builder binary(String value) {
            this.binary = value;
            return this;
        }

        public Builder probeBinary(String value) {
            this.probeBinary = value;
            return this;
        }

        public Builder videoCodec(String value) {
            this.videoCodec = value;
            return this;
        }

        public Builder preset(String value) {
            this.preset = value;
            return this;
        }

        public Builder ffmpegTimeout(Duration value) {
            this.ffmpegTimeout = value;
            return this;
        }

        public Builder onUndecodableAudio(StreamingProperties.UndecodableAudioPolicy value) {
            this.onUndecodableAudio = value;
            return this;
        }

        public Builder subtitlesEnabled(boolean value) {
            this.subtitlesEnabled = value;
            return this;
        }

        public Builder segmentDuration(Duration value) {
            this.segmentDuration = value;
            return this;
        }

        public Builder renditions(List<StreamingProperties.Rendition> value) {
            this.renditions = value;
            return this;
        }

        public Builder videoExtensions(List<String> value) {
            this.videoExtensions = value;
            return this;
        }

        public Builder extraTrackers(List<String> value) {
            this.extraTrackers = value;
            return this;
        }

        public Builder deadTrackers(List<String> value) {
            this.deadTrackers = value;
            return this;
        }

        public Builder downloadVideoOnly(boolean value) {
            this.downloadVideoOnly = value;
            return this;
        }

        public Builder offloadEnabled(boolean value) {
            this.offloadEnabled = value;
            return this;
        }

        public Builder internalPrefix(String value) {
            this.internalPrefix = value;
            return this;
        }

        public Builder redisEnabled(boolean value) {
            this.redisEnabled = value;
            return this;
        }

        public Builder rateLimitCapacity(int value) {
            this.rateLimitCapacity = value;
            return this;
        }

        public StreamingProperties build() {
            return new StreamingProperties(
                    new StreamingProperties.Storage(downloadsDir, hlsDir, Duration.ofDays(7)),
                    new StreamingProperties.Ffmpeg(
                            binary,
                            probeBinary,
                            videoCodec,
                            List.of("libx264", "libopenh264"),
                            preset,
                            ffmpegTimeout,
                            segmentDuration,
                            new StreamingProperties.Audio(
                                    List.of("libfdk_aac", "aac"), 128, 2, 48000, onUndecodableAudio),
                            new StreamingProperties.Subtitles(subtitlesEnabled),
                            new StreamingProperties.Hardware(false, "/dev/dri/renderD128"),
                            renditions),
                    new StreamingProperties.Torrent(
                            videoExtensions,
                            Duration.ofSeconds(30),
                            Duration.ofSeconds(1),
                            extraTrackers,
                            deadTrackers,
                            downloadVideoOnly,
                            new StreamingProperties.Network(
                                    StreamingProperties.Encryption.PREFER_ENCRYPTED,
                                    true,
                                    false,
                                    "",
                                    6891,
                                    200,
                                    60,
                                    600,
                                    200,
                                    200,
                                    2048,
                                    Duration.ofSeconds(8))),
                    new StreamingProperties.Transcoding(
                            new StreamingProperties.Transcoding.Pool(1, 1, 10, "test-")),
                    new StreamingProperties.Playback(offloadEnabled, internalPrefix),
                    new StreamingProperties.Redis(
                            redisEnabled,
                            Duration.ofDays(7),
                            new StreamingProperties.Redis.RateLimit(rateLimitCapacity, 20)),
                    new StreamingProperties.Providers(
                            false, java.nio.file.Path.of("providers.db"), "", "", Duration.ofDays(30), 1000),
                    new StreamingProperties.Web(List.of()));
        }
    }
}
