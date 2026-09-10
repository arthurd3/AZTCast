package com.azt.streaming.shared.storage;

/**
 * Thrown when a delete would take a video someone marked as kept.
 *
 * <p>The marker used to mean "the reaper may not have this". There is no reaper on the ladder any
 * more, so it means the only thing left for it to mean: this one was not an accident, ask again.
 * Deletion is now the single way media leaves the disk, and the one destructive operation in the
 * API — a flag a person set deliberately is the right thing to make it stop for.
 */
public class VideoIsKeptException extends RuntimeException {

    public VideoIsKeptException(String videoId) {
        super("Video " + videoId + " is marked as kept; repeat the request with force=true to delete it");
    }
}
