package com.azt.streaming.transcoding.domain;

/** Raised when a media file cannot be turned into an HLS ladder. */
public class TranscodingException extends RuntimeException {

    public TranscodingException(String message) {
        super(message);
    }

    public TranscodingException(String message, Throwable cause) {
        super(message, cause);
    }
}
