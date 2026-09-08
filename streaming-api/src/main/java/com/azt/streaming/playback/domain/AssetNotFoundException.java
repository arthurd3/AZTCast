package com.azt.streaming.playback.domain;


/** Raised when a requested HLS asset does not exist, or would fall outside the media root. */
public class AssetNotFoundException extends RuntimeException {

    public AssetNotFoundException(String videoId, String fileName) {
        super("No HLS asset '%s' for video %s".formatted(fileName, videoId));
    }
}
