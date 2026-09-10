package com.azt.streaming.shared.error;

import java.net.URI;

/**
 * Stable {@code type} URIs for the problem details this API returns.
 *
 * <p>These are identifiers, not links to fetch — RFC 9457 allows that, and a client should switch on
 * them rather than on the human-readable {@code detail} string.
 */
public final class ProblemTypes {

    private static final String BASE = "https://aztcast.dev/problems/";

    public static final URI VALIDATION_FAILED = URI.create(BASE + "validation-failed");
    public static final URI ASSET_NOT_FOUND = URI.create(BASE + "asset-not-found");
    public static final URI JOB_NOT_FOUND = URI.create(BASE + "job-not-found");
    public static final URI VIDEO_NOT_FOUND = URI.create(BASE + "video-not-found");
    public static final URI ACQUISITION_FAILED = URI.create(BASE + "acquisition-failed");
    public static final URI TRANSCODING_FAILED = URI.create(BASE + "transcoding-failed");
    public static final URI NOT_REPAIRABLE = URI.create(BASE + "not-repairable");
    public static final URI RATE_LIMITED = URI.create(BASE + "rate-limited");
    public static final URI INSUFFICIENT_STORAGE = URI.create(BASE + "insufficient-storage");
    public static final URI VIDEO_IS_KEPT = URI.create(BASE + "video-is-kept");
    public static final URI INTERNAL_ERROR = URI.create(BASE + "internal-error");

    private ProblemTypes() {}
}
