# Audio keeps the channels the source had

## Status

Accepted

Supersedes the part of [ADR-0021](0021-one-audio-rendition-shared-by-every-rung.md) that fixes the
audio rendition at 128 kbps stereo. The decision that made it *one* rendition shared by every rung
stands; what changes is what that rendition contains.

## Context

The audio policy was three fixed numbers: `bitrate-kbps: 128`, `channels: 2`, `sample-rate: 48000`.
Applied to the file this pipeline was built around — a 5.1 E-AC-3 track at 640 kbps — that meant
throwing away four channels and four fifths of the bitrate, and putting every 44.1 kHz source
through a resampler on the way.

Nothing was gained for it. Measured in Chrome 152 with `navigator.mediaCapabilities.decodingInfo`:

| | |
|---|---|
| `mp4a.40.2`, 2 channels, 128k | supported, smooth |
| `mp4a.40.2`, **6 channels, 448k** | **supported, smooth** |
| `mp4a.40.2`, 8 channels, 640k | supported |
| `ec-3` / `ac-3`, 6 channels | **not supported** |

Multichannel AAC is decoded by every mainstream browser, which then folds it down to whatever the
viewer actually has — using the viewer's own output configuration, which this service does not know
and cannot guess. The downmix was both lossy and worse-informed than the one it replaced.

## Decision

**Preserve what arrived.** `channels` and `sample-rate` take the sentinel `source`, in the same
shape as the `video-codec: auto` that [ADR-0020](0020-ffmpeg-capabilities-are-probed-not-assumed.md)
introduced. A number still forces a downmix for an operator who wants one.

**Scale the bitrate with the layout.** `bitrate-kbps-per-channel: 64` gives 128k in stereo and 384k
at 5.1 — the figure Apple's authoring specification asks for. One fixed number cannot be right for
both. `max-bitrate-kbps` caps an exotic layout, and 8 channels caps what AAC carries.

**Copy whenever encoding would change nothing.** The old test was "AAC-LC with at most two
channels", which re-encoded every 5.1 AAC-LC source purely to fold it. The question is now simply
whether the encode would alter the track; when it would not, the source is remuxed untouched.

## Consequences

- **The reference file now carries 5.1 AAC at 386 kbps**, measured off the segments, against the
  128 kbps stereo it would have produced before. `ffprobe` on the published rendition reports
  `aac (LC), 48000 Hz, 5.1`.

- **The dual advertisement from [ADR-0025](0025-a-ladder-the-browser-accepts.md) stops happening,
  by itself.** AAC is `mp4a.40.2` whatever its layout, so `isWidelyPlayable()` answers true and the
  writer emits one family. That mechanism stays for what justifies it: a build with no decoder,
  publishing passthrough.

- **A host that gains a decoder can be caught up.** See
  [ADR-0026](0026-verified-before-it-is-published-repairable-after.md): the repair endpoint grew a
  tier that re-encodes only the audio rendition, because the video rungs are already correct.
  Measured at 20 seconds against the reference video, with `1080p_000.m4s` left untouched on disk.

- **Open, and not caused by this change: hls.js does not play our demuxed audio group.** With an
  audio group the browser accepts, hls.js loads the master, the level playlist and the audio
  playlist, reports `2 bufferCodec event(s) expected`, and then both stream controllers sit in IDLE
  without loading a fragment. No error is raised.

  The output is not at fault. ffmpeg's own HLS demuxer assembles the same master correctly —
  `Audio: aac (LC), 48000 Hz, 5.1, 385 kb/s` alongside the video variants and four WebVTT tracks —
  and the audio rendition decodes cleanly on its own.

  This path had never run in a browser. Before ADR-0025 the `ec-3` codec made Chrome reject every
  variant; after it, Chrome took the video-only family, which references no audio group at all. So
  the shared-audio design has been latent since ADR-0021 and only became reachable once the audio
  became a codec a browser would accept.

  One thing it is *not*: a timeline offset introduced by rebuilding the audio separately. A ladder
  built in a single ffmpeg invocation puts the video renditions at 0.083 and the audio at 0.062,
  so a small offset between demuxed renditions is inherent to `hlsenc` rather than to the repair.

  The alternative, if this proves to be ours rather than hls.js's, is muxing the audio back into
  every variant — the shape ADR-0021 replaced. At 5.1 and 384 kbps that is roughly 325 MB of
  duplicated audio on a 22-minute episode, which is why it is the fallback and not the first move.
