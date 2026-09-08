package com.azt.streaming.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public interface ITorrentService {
    CompletableFuture<Path> downloadTorrentLink(final String magnetLink , Path torrentPath);
}
