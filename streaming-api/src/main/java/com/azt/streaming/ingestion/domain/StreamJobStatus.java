package com.azt.streaming.ingestion.domain;

/** Where a video is in the acquire -> transcode -> serve pipeline. */
public enum StreamJobStatus {
    /** The torrent is being fetched. */
    DOWNLOADING,
    /** The file is downloaded; ffmpeg is producing the HLS ladder. */
    TRANSCODING,
    /** The master playlist exists and the video can be played. */
    READY,
    /** Acquisition or transcoding failed; see {@link StreamJob#failureReason()}. */
    FAILED
}
