package com.azt.streaming.playback.domain;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Raised when a requested HLS asset does not exist, or would fall outside the media root. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class AssetNotFoundException extends RuntimeException {

    public AssetNotFoundException(String videoId, String fileName) {
        super("No HLS asset '%s' for video %s".formatted(fileName, videoId));
    }
}
