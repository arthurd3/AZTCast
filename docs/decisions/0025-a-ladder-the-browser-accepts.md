# A ladder the browser accepts

## Status

Accepted

Supersedes the part of [ADR-0021](0021-one-audio-rendition-shared-by-every-rung.md) that describes
what passthrough costs. That record says a copied E-AC-3 track "plays on Safari, iOS and tvOS and is
silent on Chrome and Firefox". The first half is right. The second is wrong, and the difference is
the whole video.

## Context

The ladder [ADR-0021](0021-one-audio-rendition-shared-by-every-rung.md) made publishable did not
play. In Chrome the player showed `Erro de mídia, recuperando (1/2)…` and stopped there.

Nothing was corrupt. All 2,063 files were on disk, every playlist ended in `EXT-X-ENDLIST`, and
every segment the playlists named was present and non-empty. hls.js said exactly what was wrong:

```
mediaError/manifestIncompatibleCodecsError   fatal=true   levels=0
```

Measured in Chrome 152 on the machine this was written on:

| `MediaSource.isTypeSupported(…)` | |
|---|---|
| `video/mp4; codecs="avc1.640028"` | **true** |
| `audio/mp4; codecs="ec-3"` | false |
| `video/mp4; codecs="avc1.640028,ec-3"` | **false** |

A variant's `CODECS` attribute describes the whole combination the player would have to assemble.
So an audio codec the browser lacks does not cost the viewer the audio — **it disqualifies the
variant**. All five rungs declared `ec-3`, all five were filtered, and hls.js was left with nothing
to play.

The reasoning in ADR-0021 was right about the mechanism and wrong about its blast radius: it
assumed the audio group could be ignored independently of the video beside it. It cannot.

## Decision

When the published audio is one only Apple's platforms decode — `ec-3`, `ac-3`, `alac` — advertise
every rung **twice**: once joined to the audio group, once video-only.

```
#EXT-X-STREAM-INF:BANDWIDTH=12397730,…,CODECS="avc1.640028,ec-3",AUDIO="aud",SUBTITLES="subs"
1080p.m3u8
…
#EXT-X-STREAM-INF:BANDWIDTH=11756909,…,CODECS="avc1.640028",SUBTITLES="subs"
1080p.m3u8
```

The same media playlists, referenced a second way. **Not one byte more on disk.** A player keeps
whichever family its `CODECS` says it can assemble.

When the audio *is* widely playable — AAC, which is what any build with a decoder produces — nothing
is duplicated and the master is exactly what it was.

`AudioPlan.WIDELY_PLAYABLE` is the table, and it is deliberately about what Media Source Extensions
accept rather than about what is legal in HLS. The two are not the same question, and it was
answering the wrong one that produced this.

## Consequences

- **The reference video plays.** Verified in Chrome against the real file: the manifest parses to
  five levels, playback runs, and all four subtitle tracks load and render — the Brazilian
  Portuguese track was read off the screen. Before: zero levels and a fatal error.

- **Subtitles ride on both families.** They are their own group and their own segments; nothing
  about them depends on which audio the player ended up with.

- **A player that supports E-AC-3 may still pick a silent variant.** Both families are eligible for
  it, and at a constrained bitrate it may land on a video-only rung. The audio-bearing entries are
  written first and carry the higher `BANDWIDTH`, which is the only nudge the format allows. This
  is the honest cost of offering both in one master, and it is a much smaller cost than the
  alternative it replaces.

- **Advertising the same URI twice is unusual.** It is not forbidden — a master playlist is a list
  of variants, not a set of media — and hls.js handles it correctly, keeping five levels rather than
  ten. It is still the kind of thing worth finding written down before finding it in a diff.

- **The video-only entries carry lower bandwidths, and they are true.** Without the audio group the
  240p rung costs 530 kbps peak rather than 1,171 — the 640 kbps E-AC-3 track was most of it. A
  browser that cannot play that audio should not be asked to budget for it.

- **The player stopped pretending it could recover.** `manifestIncompatibleCodecsError` is
  permanent: `recoverMediaError()` cannot install a codec. It now fails immediately and names the
  codec, instead of showing "recuperando (1/2)…" twice on the way to the same conclusion.
