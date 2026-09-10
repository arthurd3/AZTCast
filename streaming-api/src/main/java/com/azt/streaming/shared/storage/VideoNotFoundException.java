package com.azt.streaming.shared.storage;

/**
 * Thrown when an operation names a video the catalogue does not hold.
 *
 * <p>Distinct from a missing <em>job</em>: a job record expires while its media lives on, and a
 * video's media is reaped while nothing remembers the job that made it. Reporting one as the other
 * would tell a caller to retry an ingestion that is not the problem.
 */
public class VideoNotFoundException extends RuntimeException {

    public VideoNotFoundException(String videoId) {
        super("No video with id " + videoId);
    }
}
