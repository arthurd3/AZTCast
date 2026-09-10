package com.azt.streaming.transcoding.domain;

import java.nio.file.Path;

/** Reads the stream layout of a media file without decoding it. */
public interface MediaProbe {

    /**
     * Measures a rung this service produced, for its CODECS attribute.
     *
     * @throws TranscodingException if the file cannot be probed
     */
    ProbedVideo probe(Path file);

    /**
     * Describes an arbitrary source file, for every decision that has to be made about it.
     *
     * <p>Separate from {@link #probe} because it asks ffprobe a much larger question — channel
     * counts, frame rate, language tags, dispositions, every audio and subtitle stream — and there
     * is no reason to ask any of that of a variant playlist this service just wrote.
     *
     * @throws TranscodingException if the file cannot be probed
     */
    ProbedSource probeSource(Path file);
}
