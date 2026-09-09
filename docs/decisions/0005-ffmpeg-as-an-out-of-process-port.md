# ffmpeg stays an out-of-process port

## Status

Accepted

## Context

Transcoding shells out to the ffmpeg binary. In the original code the invocation
was inline in a 90-line method: the binary name was a string literal, the ladder
was three index-aligned `String[]`, there was no timeout, the process was never
destroyed on failure, and output went to `System.out`.

ffmpeg was also entirely undeclared — nothing installed it, nothing checked for
it, and a missing binary surfaced only after a torrent had finished downloading.

Java bindings exist (JavaCV/FFmpeg wrappers) and would remove the subprocess.

## Decision

Keep ffmpeg as an external process, behind the `MediaTranscoder` port.

A native binding would add a very large platform-specific dependency and couple
upgrades to a wrapper's release cycle, to remove a subprocess that is well
understood. The subprocess is also the reason the port earns its keep: it cannot
run in CI, so the interface is what makes the ingestion use case testable.

What changed is the handling around it: `FfmpegCommandBuilder` is a pure
function, `ProcessRunner` owns the lifecycle with a timeout and
`destroyForcibly`, the ladder is configuration, and `FfmpegHealthIndicator`
probes the *configured encoder* — a patent-free ffmpeg build starts fine and
then fails every encode.

The encoder is configurable because `libx264` is not universally present:
distributions shipping a patent-free build carry `libopenh264`. Both produce
H.264 Main@3.1, so the advertised `CODECS` stays correct either way.

## Consequences

- ffmpeg must be installed; the Docker image installs it and CI asserts it.
- Transcoding is out-of-process, so it cannot take the JVM down — but its memory
  is outside the heap, which is what container limits must account for.
- Swapping to a binding later means one adapter, not a rewrite.
