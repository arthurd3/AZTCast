# One audio rendition, shared by every rung

## Status

Accepted

Supersedes the part of [ADR-0018](0018-the-top-rung-is-copied-not-encoded.md) that decides audio
per rung. The video half of that record — never build a rung taller than the source, copy the top
one when it is already H.264 — stands unchanged.

## Context

Every rung mapped `a:0` and encoded it. A five-rung ladder ran five AAC encodes of one track and
wrote five copies of it into the segments: roughly 90 MB of duplicated audio on a 22-minute
episode, and 105 MB on the one this was measured against.

It also carried a defect. [ADR-0018](0018-the-top-rung-is-copied-not-encoded.md) gives the copied
top rung an audio bitrate of zero, deliberately, so that `peakBandwidthBps()` does not count the
container's audio twice. That zero is only ever meant to be arithmetic — but when the source's
audio is *not* AAC-LC, the same rung's audio is re-encoded, and the zero went out on the command
line:

```
-map a:0 -c:a:0 aac -b:a:0 0k -ac:a:0 2 -ar:a:0 48000
```

Which is not fatal. ffmpeg's AAC encoder reads a bitrate of zero as "use the default" and produces
128 kbps — so the manifest advertised a bandwidth computed from 0 for a stream carrying 128 kbps,
silently, on every source whose audio needed re-encoding beneath a copied video stream. Which is
most of them. No test covered it: `LadderPlannerTest` asserted the boolean that leads there and
stopped.

Apple's HLS authoring specification asks for the opposite arrangement anyway — audio offerings in
a rendition group that every variant references.

## Decision

One audio rendition for the whole ladder, in an `EXT-X-MEDIA` group.

`-var_stream_map` gains an `agroup:` on every entry and one audio-only variant:

```
v:0,agroup:aud,name:1080p  v:1,agroup:aud,name:720p  …  a:0,agroup:aud,name:audio,default:yes
```

`HlsRendition` lost its audio bitrate; a rung is a video rendition. The one audio bitrate lives on
`AudioPlan`, and `MasterPlaylistWriter` joins the two — it is the only thing that knows which
group a variant was pointed at, so it is the only thing that can write a `CODECS` naming the
combination the player will actually assemble.

**What that rendition is comes from `AudioPlanner`**, a pure function of the probe, the
preferences and what this build can decode ([ADR-0020](0020-ffmpeg-capabilities-are-probed-not-assumed.md)):

| Source | Decision |
| --- | --- |
| no audio track | none; video-only ladder |
| AAC-LC within the channel budget | copy |
| decodable here, AAC encoder present | encode to AAC |
| **not decodable, but fMP4 can carry it** (`ac3`, `eac3`, `aac`, `alac`, `flac`, `opus`, `mp3`) | **copy it through** |
| neither | per `ffmpeg.audio.on-undecodable`: drop, or fail |

The fourth row is the one that matters. fMP4 carries E-AC-3, so no decoder is needed to *deliver*
the track — only to change it. A build that cannot decode it can still publish it.

The primary track is the one the container marks default, not `a:0`. Taking the first stream
serves the commentary as the feature audio on any release that ships one, and nothing about the
output would say so.

## Consequences

- **`-b:a` with a zero value is now unreachable.** There is one audio stream and it always has a
  real bitrate, or it is copied and has no bitrate argument at all. `FfmpegCommandBuilderTest`
  asserts it across the encode, copy-video and copy-audio shapes.

- **The file that started all this now publishes.** Its E-AC-3 5.1 track is copied into the
  segments and the master says `CODECS="avc1.640028,ec-3"`. Safari, iOS and tvOS play it; Chrome
  and Firefox do not, and now they know that from the manifest rather than from a decoder error on
  the first segment. Verified end to end against the original magnet.

- **Passthrough puts a floor under the ladder.** That E-AC-3 track is 640 kbps, so every rung
  costs at least that: the 240p rung measures 352 kbps of video and 984 kbps in total. The bottom
  of the ladder stops being useful for adaptation. It is the honest cost of publishing audio this
  build cannot re-encode, it is advertised accurately, and it disappears entirely on a host with an
  E-AC-3 decoder — which `check-prereqs.sh` names the package for.

- **Bandwidth is measured, not declared.** With audio demuxed, a variant's declared bitrate could
  no longer be derived from the ladder at all — the copied rung's figure was the source container's,
  audio included. `VariantWeigher` reads each variant's own `EXTINF` durations against its segment
  sizes. This is also how the ladder's real behaviour became visible: `libopenh264` warns on every
  run that it cannot hold a target bitrate without frame skipping, and it does not.

- **`aztcast.streaming.ffmpeg.renditions[].audio-bitrate-kbps` is gone.** Configuration files that
  still carry it bind fine — unknown keys under a record are ignored — and it does nothing. The one
  audio bitrate is `ffmpeg.audio.bitrate-kbps`.

- **Roughly 85 MB less on disk per episode, and four fewer AAC encodes.** Not the reason for the
  change, but not nothing either.
