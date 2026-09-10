# Troubleshooting HLS playback

## `bufferAppend` errors

The symptom: the manifest parses, the player shows a spinner, and nothing ever
decodes. The browser is refusing to append a segment to its media source.

This checklist was written while debugging exactly that in this project. It was
recovered from the diagnostics page, which is the only record of the
investigation that existed.

- Prefer **H.264 (avc1) + AAC (mp4a.40.2)**. They are the pairing every browser
  supports.
- Make sure `master.m3u8`'s `CODECS` attribute matches what the segments
  actually contain. *(Fixed twice. First the master advertised no `CODECS` at
  all, forcing players to probe the first segment. Then it advertised one
  hardcoded `avc1.4d001f` for every rung — which is only correct at 720p,
  because the H.264 **level follows the resolution and bitrate the encoder
  settled on**, not what the command asked for. Measured: 1080p is level 4.0,
  480p and 360p are 3.0, 240p is 2.1. It is now derived per rung by probing the
  encoder's own output, so it cannot drift from reality again.)*

  A corollary worth knowing when reading old encodes: `-profile:v` must be set
  **per video stream** (`-profile:v:0`, `-profile:v:1`, …). Set globally or
  omitted, `libopenh264` falls back to Constrained Baseline and logs only a
  warning — so the stream is Baseline while the playlist claims Main.
- Check the **MIME types the server sends**:

  | File        | Content-Type                    |
  | ----------- | ------------------------------- |
  | `.m3u8`     | `application/vnd.apple.mpegurl` |
  | `.ts`       | `video/mp2t`                    |
  | `.m4s`      | `video/iso.segment`             |
  | `init.mp4`  | `video/mp4`                     |

  *(Fixed: only `.m3u8` and `.ts` were mapped; fMP4 output fell through to
  `application/octet-stream`, which browsers refuse to append. nginx also needs
  these declared — its stock `mime.types` predates HLS.)*
- Verify with `ffprobe` that segments really are H.264/AAC and that PTS are
  monotonic.
- For fMP4, keep segments short (2–6s) and make sure the init segment is valid.

## Diagnosing a specific video

Open `/diagnostics.html` and enter the video id. **Inspecionar** fetches the
master playlist and reports:

- whether it is a master or a media playlist;
- every `CODECS` string it advertises, and whether
  `MediaSource.isTypeSupported()` agrees;
- the status and `Content-Type` of the first real segment, via `HEAD`.

## Verifying without a browser

The most direct check — a real HLS client reading the ladder over HTTP:

```bash
ffprobe -v error -show_entries 'stream=codec_name,profile,width,height:format=duration' \
  http://localhost:5173/api/v1/stream/<videoId>/master.m3u8

ffmpeg -v error -i http://localhost:5173/api/v1/stream/<videoId>/master.m3u8 \
  -frames:v 30 -f null -
```

If those work and the browser does not, the problem is client-side.

## "It parses but never buffers" in an automated browser

If you are driving Chrome from a script and the manifest parses but
`FRAG_LOADING` never fires, check `document.visibilityState`. Chrome throttles
background tabs, and hls.js's buffering loop is timer-driven, so it stalls
entirely. This is not a bug in the player.

## `Error selecting an encoder`

No H.264 encoder at all. `aztcast.streaming.ffmpeg.video-codec` defaults to `auto`,
which picks the first entry of `video-codec-preference` this build carries, so this
means the build carries none of them — or that the setting was pinned to an encoder
that is not there.

```bash
./scripts/check-prereqs.sh          # names the encoder auto will pick, or says there is none
```

`GET /actuator/health` reports `videoEncoder` and goes `DOWN` when nothing resolves.
See [ADR-0020](decisions/0020-ffmpeg-capabilities-are-probed-not-assumed.md).

## `no decoder found for: eac3` (or hevc, dts, truehd)

This one used to fail the ingestion after the whole torrent had been downloaded. It
does not any more, and the reason is worth knowing because the outcome differs by
codec.

Distributions shipping a patent-free ffmpeg — Fedora's `ffmpeg-free`, among others —
carry no E-AC-3 decoder, and E-AC-3 is the audio of essentially every AMZN WEB-DL.
The decision is now made from the probe, before ffmpeg starts, and logged:

```
WARN  Audio for videoId …: this ffmpeg build has no eac3 decoder;
      copying the eac3 track into the segments untouched
```

| Source audio | This build can decode it | Result |
| --- | --- | --- |
| AAC-LC, stereo | — | copied, `mp4a.40.2` |
| anything | yes | re-encoded to AAC, `mp4a.40.2` |
| `ac3` `eac3` `flac` `alac` `opus` | no | **copied through**, real CODECS (`ec-3`, `ac-3`, …) |
| `dts` `truehd` | no | dropped; video-only ladder |

A copied E-AC-3 track plays on Safari, iOS and tvOS. Everywhere else the video
plays **silently** — and getting that right took a second attempt, because a
variant's `CODECS` attribute describes the whole combination it would assemble:

```
MediaSource.isTypeSupported('video/mp4; codecs="avc1.640028"')        → true
MediaSource.isTypeSupported('audio/mp4; codecs="ec-3"')              → false
MediaSource.isTypeSupported('video/mp4; codecs="avc1.640028,ec-3"')  → false
```

An audio codec the browser lacks does not cost it the audio, it disqualifies the
whole variant — so the first version of this left Chrome with zero playable levels
and `manifestIncompatibleCodecsError`. Every rung is now advertised twice, once
joined to the audio group and once video-only, from the same files. See
[ADR-0025](decisions/0025-a-ladder-the-browser-accepts.md).

Passthrough also puts a 640 kbps floor under every rung that carries the audio,
which makes the bottom of that family much less useful for adaptation. The
video-only family is not affected: its 240p rung costs 530 kbps rather than 1,171.

To decode it here instead — which gives 128 kbps AAC on every rung and removes the
floor — install a full ffmpeg. `./scripts/check-prereqs.sh` names the package for
this distribution:

```bash
./scripts/check-prereqs.sh
# Missing: hevc eac3
# To decode it here instead: RPM Fusion: sudo dnf install ffmpeg libavcodec-freeworld
```

`GET /actuator/health` lists `missingDecoders` and the configured
`onUndecodableAudio` policy (`passthrough`, `drop` or `fail`). Nothing here takes the
service DOWN: a missing decoder is degraded, not broken.

## A rung is bigger than its configured bitrate

`BANDWIDTH` and `AVERAGE-BANDWIDTH` in the master playlist are **measured** off the
segments on disk, not derived from `renditions[].video-bitrate-kbps`. So a mismatch
between the two is real, and usually means the encoder is not holding its target.

`libopenh264` says so itself, on every run:

```
[libopenh264] Warning:bEnableFrameSkip = 0, bitrate can't be controlled for
RC_QUALITY_MODE, RC_BITRATE_MODE and RC_TIMESTAMP_MODE without enabling skip frame.
```

It cannot hit a target bitrate without dropping frames, and dropping frames is worse.
The manifest tells the truth about what it produced, which is what keeps a player from
choosing a rung it cannot sustain. `libx264` holds its targets; `check-prereqs.sh` will
say whether this host has it.

## A video that used to play and now does not

Something removed or truncated its media, or it was published by a version of this
service whose manifests a browser refuses. Ask the API which:

```bash
curl -X POST http://localhost:8080/api/v1/videos/<videoId>/repair
```

It inspects what is published and does the cheapest thing that fixes it — rewriting
the manifests when the segments are intact, transcoding again from the retained
download when they are not, and re-fetching the torrent when that download has been
reaped too. **The videoId never changes**, so every existing link keeps working.

`200` means there was nothing to do. `202` means work started, and the job is
followable on `GET /api/v1/videos/{videoId}` exactly like an ingestion. `422` means
the media is incomplete, the download is gone, and no magnet was recorded — which is
the state of anything ingested before the sidecar carried one.

In the player, a fatal **network** error grows a **Reparar** button. A codec error
does not, because there is nothing on the server to repair.
