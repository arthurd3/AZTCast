package com.azt.streaming.ingestion.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for starting an ingestion.
 *
 * <p>The record component stays {@code magnetUrl}: that is the JSON field name on the wire, and an
 * external client depends on it. Only the Java type name changed.
 */
public record CreateStreamJobRequest(
        @NotBlank(message = "magnetUrl must not be blank") String magnetUrl) {}
