# The top rung is copied, not encoded

## Status

Accepted

Supersedes the part of [ADR-0008](0008-cmaf-ladder-in-one-pass.md) that assumes every rung of
the ladder is produced by an encoder. The one-pass, one-invocation shape it established stands;
what changes is that the best rung is now usually a remux of the source rather than an encode.

## Context

Every ingestion decoded the source and re-encoded it into five fixed rungs. That was wrong in
both directions at once, and the second error was the expensive one.

**Upward.** The ladder was configuration, not a function of the input, so a 480p torrent was
scaled up to 1080p and 720p. Those are the two slowest rungs to encode, and both produce a
picture strictly worse than the source they were invented from. Nothing in the pipeline
noticed, because nothing compared the ladder to what arrived.

**Downward.** Most video torrents are already H.264 in MP4 or Matroska. The pipeline decoded
that and re-encoded it to produce an H.264 rung at roughly the same resolution — spending the
largest share of the encode budget to arrive, a generation of quality worse, where it already
was.

**And there is no hardware to fall back on.** The obvious answer to "encoding is slow" is a
GPU. Measured on the development machine (Ryzen 9 5900XT, Radeon RX 9070 XT, Fedora,
ffmpeg 8.1.2), there is no H.264 encoder in hardware to reach for:

| Encoder        | Result                                                            |
| -------------- | ----------------------------------------------------------------- |
| `libopenh264`  | works — the only H.264 encoder present                            |
| `h264_vaapi`   | `No usable encoding profile found` — RDNA4 exposes no H.264 encode |
| `h264_amf`     | `libamfrt64.so.1` missing — AMF runtime is not installed           |
| `h264_nvenc`   | no NVIDIA device                                                   |
| `av1_vaapi`    | works, but AV1 in HLS breaks the CODECS contract and player support |
| `libx264`      | absent — Fedora ships a patent-free ffmpeg                          |

So the fastest available H.264 encoder is also the lowest-quality one, and it was being asked
to do the most work on the rung that matters most.

## Decision

Plan the ladder from the source instead of from configuration alone.
`transcoding/domain/LadderPlanner` is a pure function of the configured rungs and what ffprobe
found, and it applies three rules:

- **Never build a rung taller than the source.** A configured rung above the source height is
  dropped. If that empties the ladder — a source shorter than the shortest rung — the shortest
  rung is kept and does upscale, because the alternative is a video with nothing playable.

- **Copy the top rung when the source is already H.264.** It becomes a rung at the source's
  native resolution, named `<height>p`, mapped straight off the input with `-c:v copy`.
  Configured rungs within 10% of the source height are dropped as near-duplicates, which is
  what stops an anamorphic 1082-pixel source encoding a 1080p rung underneath a copy of itself.

- **Copy the audio only when it is AAC-LC.** `ProbedVideo.AAC_LC` is a constant in the CODECS
  string, so copying HE-AAC would have the playlist claim `mp4a.40.2` for something that is
  not — the same class of lie about a stream that measuring the output exists to prevent.
  Anything else is re-encoded to AAC beside the copied video, which costs almost nothing.

A copied stream cannot pass through `-filter_complex`, so the command grew a second shape: the
copy rung maps `0:v:0` directly and the `split` filter now counts only the encoded rungs. For
the same reason `-force_key_frames`, `-ac` and `-ar` became per-stream — aimed globally they
would also be aimed at a stream with no encoder to obey them.

## Consequences

- **The best rung is now bit-identical to the source.** Not "visually lossless": the same
  bitstream. `RealFfmpegLadderTest` asserts it by remuxing both to Annex B and comparing MD5,
  and that assertion was verified by mutation — disabling the copy fails it.

- **Segments across rungs are no longer exactly aligned.** A copied rung breaks on the
  keyframes the source already had, so its `#EXTINF` durations approximate `hls_time` rather
  than matching it. The HLS specification wants variants segment-aligned for seamless
  switching, and this is a deliberate deviation from that: hls.js handles it for VOD, and
  `EXT-X-INDEPENDENT-SEGMENTS` remains true, because each copied segment does start on a
  keyframe. The alternative is giving up the lossless top rung, which is the whole point.

- **Encode time and CPU both fall, by a variable amount.** For a 1080p H.264 source the most
  expensive rung disappears entirely; for a 480p source two invented rungs disappear with it.
  The `aztcast.transcode` timer is now tagged with the number of rungs actually built rather
  than the number configured, because a 480p ingestion and a 1080p one are no longer the same
  job and averaging them would hide exactly this.

- **The CODECS attribute matters more than before, not less.** Nothing chose the copied rung's
  profile or level, so a source in High@4.1 — which plenty of devices refuse — is advertised as
  High@4.1. That is what measuring the output already did; it is now load-bearing rather than a
  confirmation.

- **HEVC and AV1 sources are still fully re-encoded.** They could be copied into HLS, but the
  master playlist, the CODECS derivation and the players this is tested against are all built
  around AVC, and a top rung many browsers silently refuse is worse than a slow encode.
