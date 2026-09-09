package com.azt.streaming.acquisition.domain;

/** Raised when a magnet cannot be turned into a usable video file. */
public class TorrentDownloadException extends RuntimeException {

    public TorrentDownloadException(String message) {
        super(message);
    }

    public TorrentDownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
