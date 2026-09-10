package com.azt.streaming.ingestion.web.dto;

/**
 * How much room the library has, and how much of it the library is using.
 *
 * <p>Exists because nothing deletes itself any more. A person who has been told their videos are
 * theirs to keep needs to be able to see what that is costing, and to see the floor coming before
 * an ingestion is refused at it rather than after.
 *
 * @param usableBytes what could still be written to the media volume, or null if it could not be
 *     read. Null rather than zero on purpose: "no answer" and "no room" are different, and only one
 *     of them is a reason to stop.
 * @param totalBytes the size of that volume, or null on the same terms
 * @param mediaBytes what the HLS ladders occupy, summed from the catalogue rather than walked again
 * @param videoCount how many videos that is
 * @param minFreeBytes the floor an ingestion refuses below, so the page can say where it is
 */
public record StorageResponse(
        Long usableBytes, Long totalBytes, long mediaBytes, int videoCount, long minFreeBytes) {}
