package com.azt.streaming.shared.storage;

/**
 * Thrown when starting an ingestion would leave the media volume below its configured floor.
 *
 * <p>Raised before any work begins, which is the whole value of it. Nothing reclaims media space on
 * its own any more, so the failure this replaces was not "the download is refused" but "the download
 * runs for two hours, fills the disk, and takes the encode of an unrelated video down with it".
 */
public class InsufficientStorageException extends RuntimeException {

    public InsufficientStorageException(long usableBytes, long requiredBytes) {
        super("Only %d byte(s) free on the media volume; %d are required before starting an ingestion"
                .formatted(usableBytes, requiredBytes));
    }
}
