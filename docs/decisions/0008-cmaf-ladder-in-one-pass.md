# A CMAF ladder, keyframe-aligned, encoded in one pass

## Status

Accepted

Supersedes one factual claim in
[ADR-0005](0005-ffmpeg-as-an-out-of-process-port.md): *"Both produce H.264
Main@3.1, so the advertised CODECS stays correct either way."* That is not true,
and the reasoning below is what replaces it.

## Context

The ladder had two rungs, encoded by two ffmpeg processes in a loop — so the
source was decoded once per rung. Nothing constrained keyframe placement, which
means segment boundaries did not reliably fall on one, and a player cannot
switch rungs cleanly at a boundary that is not a keyframe. Segments were MPEG-TS.

And the master playlist advertised a single hardcoded
`CODECS="avc1.4d001f,mp4a.40.2"` for every rung. Measured against the real
encoder:

| rung  | measured    | advertised  |
| ----- | ----------- | ----------- |
| 1080p | Main @ 4.0  | Main @ 3.1  |
| 720p  | Main @ 3.1  | Main @ 3.1  |
| 480p  | Main @ 3.0  | Main @ 3.1  |
| 360p  | Main @ 3.0  | Main @ 3.1  |
| 240p  | Main @ 2.1  | Main @ 3.1  |

The level is not a property of the request; the encoder derives it from the
resolution and bitrate it settled on. So one constant cannot be right for a
ladder, and the two-rung version was already wrong about 240p. `CODECS` is what
a player reads to decide whether it can play a rendition before fetching a byte
of it — `docs/troubleshooting-hls.md` records what a wrong one costs.

Separately, `ffprobe` had been configured, validated at startup and never
called since it was introduced.

## Decision

**One ffmpeg invocation for the whole ladder**, using `split` to decode once and
feed every scaler, with five rungs spaced ~1.6–1.8× apart.

**Keyframes forced by time**, `-force_key_frames expr:gte(t,n_forced*N)`, not by
`-g N`. The usual advice assumes a known frame rate; the source here is an
arbitrary torrent and may be variable frame rate, where a frame count is the
wrong unit. Every segment then starts on a keyframe at any frame rate.

**CMAF/fMP4 segments** rather than MPEG-TS: less container overhead than TS's
188-byte packets, and the format LL-HLS and DASH would need later.

**`CODECS` is measured, not declared.** After encoding, ffprobe reads each rung's
variant playlist and the RFC 6381 string is built from what it reports. This
gives `probe-binary` its first consumer.

Two constraints shaped the command, and both are guarded by tests because
neither fails loudly:

- **Filenames stay flat.** The standard multi-rung recipe writes
  `stream_0/playlist.m3u8`. The frozen playback mapping takes a *single* path
  segment, so a subdirectory makes the whole ladder unreachable with no error
  anywhere in the encode. The `name:` key in `-var_stream_map` keeps `%v`
  expanding to the rung name.
- **`-master_pl_name` is never passed.** ffmpeg writes the master when the encode
  *starts*, and the presence of `master.m3u8` is this service's readiness
  sentinel — it would announce a ladder whose variants do not exist yet.
  `MasterPlaylistWriter` still writes it, last.

`-profile:v:N main` is set per video stream. Without it `libopenh264` drops to
Constrained Baseline silently, which is how ADR-0005's claim came to be wrong.

## Consequences

- The source is decoded once instead of once per rung, and there are five rungs
  instead of two, so a player has a usable step per doubling of bandwidth.
- **One job now costs much more concurrent CPU and out-of-heap memory.**
  `transcoding.pool.max-size` came down from 4 to 2 in the same change. It and
  the container memory limit move together — `docs/runbook.md` records the
  exit-137 that ignoring this produces.
- Segments are `.m4s` with a `_init.mp4` per rung. Both are already in
  `HlsMediaTypes` and the nginx mime map. **Existing `.ts` ladders are not
  migrated**; they keep working, and are regenerated on re-ingestion.
- A probe failure degrades to the old hardcoded string with a warning rather
  than failing the ingestion: an approximate `CODECS` on a playable ladder beats
  no ladder.
- `RealFfmpegLadderTest` runs the actual binaries, because the two defects above
  are invisible to any assertion about the command's shape. It skips when ffmpeg
  is absent.
