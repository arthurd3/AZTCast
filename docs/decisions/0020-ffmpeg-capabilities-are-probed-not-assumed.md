# ffmpeg capabilities are probed, not assumed

## Status

Accepted

Supersedes the part of [ADR-0005](0005-ffmpeg-as-an-out-of-process-port.md) that treats the
encoder as a configuration value. ffmpeg remains an out-of-process port with a pure command
builder and a `ProcessRunner` that owns the subprocess; what changes is where the codec names in
that command come from.

## Context

An ingestion downloaded 756 MB of a perfectly ordinary release and then died:

```
[aist#0:1/eac3] Decoding requested, but no decoder found for: eac3
ffmpeg exited with code 234
```

E-AC-3 is the audio of essentially every AMZN WEB-DL, and Fedora's `ffmpeg-free` carries no
decoder for it. The file was not unusual. The pipeline was.

Three separate assumptions had been made about the binary, and all three were wrong on the
machine this project is developed on:

- `application.yml` named `libx264`, which no patent-free distribution ships. The workaround was
  `application-local.yml` saying "actually `libopenh264` here" — a per-machine profile that
  covers exactly the machines someone remembered to write one for.
- Nothing anywhere asked about **decoders**, and that is the half that failed. A build can have a
  perfectly good H.264 encoder and no E-AC-3 decoder; nothing in the service could tell.
- `FfmpegHealthIndicator` checked one encoder and fed its answer to nobody. The command builder
  read the configured name regardless.

So every question that could only be answered by running ffmpeg was answered by running ffmpeg on
a real job, at the point of failure, after the bandwidth had been spent.

## Decision

Ask the binary once, at first use, and plan from the answer.

`FfmpegCapabilities` runs `-encoders`, `-decoders` and `-hwaccels`, parses the listing table and
memoises a `FfmpegSupport` value. It indexes both the implementation name and the codec it serves
— the description's trailing `(codec h264)` — because a build carrying only `libopenh264` can
decode H.264, and indexing implementations alone concludes otherwise.

Three things read it:

- **`video-codec: auto`** is the shipped default. The first entry of `video-codec-preference` the
  build actually has is used. `application-local.yml` lost its ffmpeg block entirely.
- **`AudioPlanner`** decides what to do with the source's audio against `canDecode`, before ffmpeg
  starts. See [ADR-0021](0021-one-audio-rendition-shared-by-every-rung.md).
- **`-preset`** is emitted only when the resolved encoder defines the option, asked via
  `-h encoder=<name>`. `libopenh264` has none, and passing it one puts a warning in the log on
  every encode.

**An unreadable listing means "yes", not "no".** `FfmpegSupport.unknown()` answers every query
affirmatively. A stub binary in a test, a hung process, a future ffmpeg that reformats the table:
all of them produce the behaviour the pipeline had before any of this existed — trust the
configuration, let ffmpeg complain. Inferring "this build supports nothing" from one unreadable
listing would turn a parsing problem into a service that refuses every file it is given.

**Hardware is detected and not used.** `h264_vaapi` is listed by `-encoders` on the development
machine and fails every attempt to open it with `Function not implemented`, because the installed
Mesa driver exposes no H.264 profile at all. A listing says what was compiled in; only an encode
says what works, so `hardwareEncoderWorks` runs a one-frame encode against the render node. The
result is reported on `/actuator/health` and by `scripts/check-prereqs.sh`. The encode path stays
in software; `FfmpegCommandBuilder.videoArgs` is where a hardware branch will go.

## Consequences

- **The service runs on whatever Linux it is put on.** That was the requirement behind this
  change, and it is now a property of the code rather than of a profile someone maintains.

- **A degraded build is degraded, not broken.** `/actuator/health` stays UP with a
  `missingDecoders` detail, because the pipeline adapts to a missing decoder. It goes DOWN only
  when there is no usable H.264 encoder at all, which is the one case with no ladder to build.

- **`scripts/check-prereqs.sh` explains the host rather than checking one box.** It reports the
  encoder `auto` will pick, which notable decoders are missing, whether VAAPI actually opens, and
  names the package that would fix it for this distribution — `dnf`, `apt`, `pacman`, `zypper` or
  `apk`. It never fails the run, because the service does not need it to.

- **One more subprocess at startup, three commands, memoised.** Resolved lazily rather than in a
  `@PostConstruct`, so a slow or missing binary cannot stop the context from coming up and
  reporting the problem on the health endpoint.

- **Pinning an encoder still works and now fails loudly.** Naming one that this build does not
  carry throws at the first job with a message saying so, instead of producing an ffmpeg command
  that is rejected after a ladder has been planned around it.
