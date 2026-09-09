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

The configured `aztcast.streaming.ffmpeg.video-codec` is not present in this
ffmpeg build. Distributions that ship a patent-free ffmpeg — Fedora's default,
among others — carry `libopenh264` instead of `libx264`.

```bash
./scripts/check-prereqs.sh          # reports exactly this
ffmpeg -hide_banner -encoders | grep 264
```

The `local` profile already selects `libopenh264`. The Docker image installs an
ffmpeg that has `libx264`, which is the default everywhere else.

`GET /actuator/health` reports the configured encoder and goes `DOWN` with
`"encoder not available in this ffmpeg build"` when it is missing.
