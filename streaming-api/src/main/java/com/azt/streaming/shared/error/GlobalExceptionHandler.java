package com.azt.streaming.shared.error;

import com.azt.streaming.acquisition.domain.TorrentDownloadException;
import com.azt.streaming.ingestion.domain.StreamJobNotFoundException;
import com.azt.streaming.playback.domain.AssetNotFoundException;
import com.azt.streaming.shared.ratelimit.RateLimitExceededException;
import com.azt.streaming.transcoding.domain.TranscodingException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns exceptions into RFC 9457 {@code application/problem+json} responses.
 *
 * <p>There was no exception handling of any kind before this: every failure was a bare
 * {@code RuntimeException} and reached the client as a Whitelabel error page carrying a stack trace.
 * Domain code throws plain exceptions; converting them to a wire format happens here, once.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(AssetNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleAssetNotFound(AssetNotFoundException e) {
        // Debug, not warn: a player polling for a video that is still transcoding hits this on
        // every attempt, and it is not an error condition.
        log.debug("Asset not found: {}", e.getMessage());

        // no-store is load-bearing, not hygiene. A 404 is heuristically cacheable under RFC 9111,
        // and the absence of master.m3u8 is precisely this service's "not ready yet" signal. Let a
        // browser or proxy cache that 404 and the video stays unplayable to that client long after
        // transcoding finished — the one failure the polling protocol cannot recover from.
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .cacheControl(CacheControl.noStore())
                .body(problem(HttpStatus.NOT_FOUND, ProblemTypes.ASSET_NOT_FOUND, "Asset not found", e.getMessage()));
    }

    @ExceptionHandler(StreamJobNotFoundException.class)
    public ProblemDetail handleJobNotFound(StreamJobNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, ProblemTypes.JOB_NOT_FOUND, "Job not found", e.getMessage());
    }

    /**
     * A specific handler is required, not optional: without it the catch-all {@code Exception}
     * handler below would turn every throttled request into a logged 500.
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimitExceeded(RateLimitExceededException e) {
        log.warn("Rate limited: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, e.retryAfter().toSeconds())))
                .cacheControl(CacheControl.noStore())
                .body(problem(
                        HttpStatus.TOO_MANY_REQUESTS,
                        ProblemTypes.RATE_LIMITED,
                        "Too many requests",
                        "Ingestion is rate limited. Retry in %d seconds."
                                .formatted(Math.max(1, e.retryAfter().toSeconds()))));
    }

    @ExceptionHandler(TorrentDownloadException.class)
    public ProblemDetail handleTorrentDownload(TorrentDownloadException e) {
        log.warn("Acquisition failed", e);
        return problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ProblemTypes.ACQUISITION_FAILED,
                "Acquisition failed",
                e.getMessage());
    }

    @ExceptionHandler(TranscodingException.class)
    public ProblemDetail handleTranscoding(TranscodingException e) {
        log.error("Transcoding failed", e);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ProblemTypes.TRANSCODING_FAILED,
                "Transcoding failed",
                "The media could not be transcoded.");
    }

    /** Path-variable and request-param constraints, e.g. the videoId format. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException e) {
        ProblemDetail problem =
                problem(
                        HttpStatus.BAD_REQUEST,
                        ProblemTypes.VALIDATION_FAILED,
                        "Validation failed",
                        "One or more request values are invalid.");
        problem.setProperty(
                "errors",
                e.getConstraintViolations().stream()
                        .map(
                                (ConstraintViolation<?> violation) ->
                                        Map.of(
                                                "field", violation.getPropertyPath().toString(),
                                                "message", violation.getMessage()))
                        .toList());
        return problem;
    }

    /** Request-body validation, i.e. the {@code @Valid @RequestBody} on ingestion. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        ProblemDetail problem =
                problem(
                        HttpStatus.BAD_REQUEST,
                        ProblemTypes.VALIDATION_FAILED,
                        "Validation failed",
                        "The request body is invalid.");
        problem.setProperty(
                "errors",
                e.getBindingResult().getFieldErrors().stream()
                        .map(
                                error ->
                                        Map.of(
                                                "field", error.getField(),
                                                "message",
                                                        error.getDefaultMessage() == null
                                                                ? "invalid"
                                                                : error.getDefaultMessage()))
                        .toList());
        return ResponseEntity.badRequest().body(problem);
    }

    /** Last resort. Never let an unmapped exception reach the client as a stack trace. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ProblemTypes.INTERNAL_ERROR,
                "Internal error",
                "The request could not be completed.");
    }

    private static ProblemDetail problem(
            HttpStatus status, URI type, String title, @Nullable String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(type);
        problem.setTitle(title);
        problem.setDetail(detail == null ? title : detail);
        return problem;
    }

}
