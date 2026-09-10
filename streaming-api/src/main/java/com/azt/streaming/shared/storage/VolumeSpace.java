package com.azt.streaming.shared.storage;

/**
 * How big the volume holding the media roots is, and how much of it is still writable.
 *
 * <p>The volume rather than the directories: free space is a property of the filesystem, and the two
 * media roots are siblings precisely so that one number covers both.
 *
 * @param totalBytes the size of the filesystem
 * @param usableBytes what this process could still write to it, which is not the same as unallocated
 *     space — reserved blocks and quotas are already subtracted
 */
public record VolumeSpace(long totalBytes, long usableBytes) {}
