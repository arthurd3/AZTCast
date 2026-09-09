package com.azt.streaming.transcoding.domain;

import java.nio.file.Path;

/** Reads the stream layout of a media file without decoding it. */
public interface MediaProbe {

    /**
     * @throws TranscodingException if the file cannot be probed
     */
    ProbedVideo probe(Path file);
}
