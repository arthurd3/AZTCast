# Transcode progress is its own figure

## Status

Accepted

Amends [ADR-0013](0013-an-ingestion-survives-the-page-that-started-it.md), which established that
`progressPercent` means the download and only the download. That stays true. This adds a second
field beside it rather than changing what the first one means.

## Context

`StreamJob.transcoding()` set `progressPercent` to 100. Deliberately: the download really was
finished, and the field is documented as the download's.

The consequence was that the longest, least visible stage of the pipeline ran behind a full
progress bar. A feature-length source spends minutes there. Whether it was working or wedged looked
identical, from the API and from the player, and the only way to tell was to read the server log.

The reason given was sound at the time: "ffmpeg reports nothing this pipeline reads, and an
invented bar that stalls is worse than no bar." ffmpeg does report — `-progress` emits `key=value`
lines every second — the pipeline just never asked for them.

## Decision

Add `-progress pipe:1 -nostats` to the ladder command and parse `out_time_us` against the source
duration, which `ProbedSource` now carries.

`StreamJob` gains `transcodePercent`, nullable. Two fields rather than one reused, because they
measure different work: a client told that `progressPercent` means the download would watch it
jump back to zero and climb a second time. Nullable rather than zero-defaulted, so
`NON_NULL` serialisation lets a client distinguish "not started" from "0% done" without a third
field to say which.

Only whole points are reported, and only increases. Each one costs a repository write, so a
per-second float would spend a Redis round trip to move a bar by nothing; and a source whose
declared duration is shorter than its real one would otherwise walk the bar backwards.

`ProcessRunner.run` gains an overload taking a line watcher, invoked on the drain thread. The
progress consumer must not block there: that thread is what empties the pipe, and a pipe nobody
empties is a process that hangs.

## Consequences

- **`recordTranscodeProgress` filters on status, and has to.** The watcher runs on ffmpeg's output
  drain thread, which outlives the process by however long the pipe takes to close, so a final tick
  can land after the job is already READY. Same shape as `recordProgress`, same reason.

- **`-nostats` came with it.** ffmpeg's human `frame= … fps= …` carousel writes to stderr, which
  this pipeline merges into stdout and logs at DEBUG. Turning it off is why the progress lines can
  be parsed out of a merged stream at all: nothing else ffmpeg prints has the shape `key=value`.

- **Measured on the reference ingestion**: the encode reported 1% through 100% across roughly five
  minutes, one tick every few seconds. The player's step track shows it against
  "Transcodificando" and nothing against the other stages.

- **A source with no declared duration reports nothing**, rather than a percentage of an unknown
  total. The original objection was right; it just did not apply to the common case.
