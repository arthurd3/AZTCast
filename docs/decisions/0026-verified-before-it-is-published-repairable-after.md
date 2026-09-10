# Verified before it is published, repairable after

## Status

Accepted

Amends [ADR-0003](0003-filesystem-as-the-store.md), which makes the filesystem the durable store and
`master.m3u8` the signal that a video is ready.

## Context

The catalogue lists a video if and only if `master.m3u8` exists. That file was written whenever
ffmpeg exited zero — which is a much weaker claim than everything downstream was treating it as.
Nothing had ever opened a variant playlist to check that the segments it names are on disk, and
nothing had checked that a browser could assemble any of it.

Both failed. The second one shipped a video that was listed as ready and that no browser would play
([ADR-0025](0025-a-ladder-the-browser-accepts.md)).

And once a video did break, there was no way back. Given only a videoId on disk:

- job state is in memory by default, and expires under a TTL when Redis is on;
- the magnet claim is keyed by infohash, not by videoId, and `InterruptedJobReaper` releases it at
  every startup;
- `meta.json` recorded a title and nothing else;
- the raw download is reaped on the same seven-day schedule, with no exemption for a video someone
  explicitly kept.

Nothing on the host knew where the bytes had come from. The only remedy was to find the magnet
again by hand and ingest it as a **second** video, under a second id — leaving the broken one listed
and every existing link pointing at it.

## Decision

**Verify before publishing.** `LadderIntegrity` runs between rendering the master playlist and
writing it. It refuses a ladder whose playlists are unreadable or unterminated, whose segments or
`EXT-X-MAP` init segments are missing or zero-length, whose advertised subtitles were never written,
or **none of whose variants a mainstream browser can assemble**. A refusal throws, and the failure
path that already existed discards the half-written directory. The sentinel now means what
everything assumed it meant.

The check reads the *rendered bytes*, not the plan that produced them. A writer that stopped
emitting the video-only family would still satisfy an assertion about the plan.

**Record the origin.** `meta.json` gains `magnetUrl` beside `title`. It sits with the media so the
two share one lifetime, for the same reason the title does.

**Repair in place, under the same id.** `POST /api/v1/videos/{videoId}/repair` inspects what is
published and does the cheapest thing that fixes it:

| Found | Action | Cost |
|---|---|---|
| sound and playable | nothing | ms |
| media intact, manifests not | rebuild the manifests | seconds, no encoder runs |
| segments missing, download retained | discard and transcode again | minutes |
| download reaped, magnet recorded | fetch the torrent again | a download plus an encode |
| download reaped, no magnet | `422`, saying so | — |

**The id never changes.** `startIngestion` mints a fresh UUID — deliberately, it is starting
something new — so this is a separate path beside it rather than an argument to it. A viewer's link,
the library card, a bookmark and the keep marker are all `videoId`; re-ingesting would produce a
second video and leave the first one broken and listed.

## Consequences

- **The library that shipped broken fixes itself in seconds.** The reference video's segments were
  bit-for-bit correct and only its master was unusable, so the repair rewrote five manifests and
  finished in two seconds. Re-encoding would have spent five minutes producing identical bytes.
  Verified against the real file; a second call answered `NOTHING_TO_DO`.

- **The first repair found a bug in the checker.** It reported that all four subtitle playlists
  "declare no `#EXT-X-MAP`" — which is correct and irrelevant, because WebVTT has no init segment.
  An init segment is now required only of playlists whose segments are fMP4. Without that fix every
  healthy ladder would have reported four faults forever and no repair would ever have converged.

- **A repair is followable exactly like an ingestion.** It writes a `StreamJob` under the same id,
  so the player polls `GET /api/v1/videos/{videoId}` with the code it already has.

- **It is rate limited, unlike `/keep`.** At its most expensive it spends a full download and a full
  encode, which is precisely what the limit exists to bound.

- **`VariantWeigher` and the checker read through one parser.** `VariantPlaylist` was extracted
  because the weigher skipped every line beginning with `#` — and so skipped `EXT-X-MAP`, the one
  file a variant cannot be decoded without. A second hand-written parser would have inherited that
  gap or invented its own.

- **Videos ingested before this change cannot be re-fetched.** Their sidecars name no magnet. They
  are still repairable from a retained download, and the `422` says which of the two states they are
  in rather than failing vaguely.

- **The player offers repair only where it would help.** On a fatal network error — what a ladder
  missing segments looks like from the browser — the status banner grows a **Reparar** button.
  Deliberately not on a codec error: there is nothing on the server to fix, and a button there would
  misdescribe the cause.
