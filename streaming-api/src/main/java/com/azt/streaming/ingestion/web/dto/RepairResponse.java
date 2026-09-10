package com.azt.streaming.ingestion.web.dto;

import com.azt.streaming.ingestion.domain.RepairAction;

/**
 * What a repair request found and what it started doing about it.
 *
 * <p>The action matters more than the status code, because two of them are asynchronous and cost
 * wildly different amounts: rebuilding a manifest is done before a viewer refreshes, re-fetching a
 * torrent is not. A client that wants to follow along polls
 * {@code GET /api/v1/videos/{videoId}} exactly as it does for an ingestion.
 *
 * @param videoId the video that was inspected — unchanged, which is the whole point of repairing
 *     rather than re-ingesting
 * @param action what was needed
 * @param detail the faults that were found, or a sentence saying there were none
 */
public record RepairResponse(String videoId, RepairAction action, String detail) {}
