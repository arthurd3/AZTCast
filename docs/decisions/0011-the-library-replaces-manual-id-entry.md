# The library replaces manual videoId entry

## Status

Accepted

Supersedes the "`videoId` entry stays" decision in
[ADR-0010](0010-the-player-drives-ingestion.md).

## Context

ADR-0010 kept the `videoId` text field as the way to replay something ingested
earlier. That was defensible while it was the *only* way, but it asks the viewer
for a UUID the interface never shows them. The id appears once, in the progress
block of the ingestion that produced it, and nowhere afterwards — so "replay
something from last week" meant having copied a UUID out of the page at the time,
or reading it out of `docker exec ls`.

Meanwhile the media was always enumerable. `hls-dir/` is one directory per video,
and `master.m3u8` is written last, so the disk already knew the answer.

The obvious implementation — list the job records — is wrong here, and the box
this was built on demonstrates why. Redis runs with `--save ""`, so a restart
dropped every `aztcast:v1:job:*` key while the HLS output survived on its volume:

```
GET /api/v1/videos/2724a02c-…              -> 404
GET /api/v1/stream/2724a02c-…/master.m3u8  -> 200
```

Three watchable videos, zero job records. Redis is also off by default
([ADR-0009](0009-redis-for-state-not-for-media.md)), which is what `make dev`
runs, and job records expire under a TTL even when it is on.

## Decision

`GET /api/v1/videos` lists what is on disk, the player renders it as a clickable
library at `/`, and watching happens on a page of its own. The `videoId` field is
gone.

- **Watching is its own page**, `/player.html?v=<videoId>`. Putting the id in the
  URL is what gives a video an address: it can be linked, bookmarked and reloaded,
  and returning to the library is the browser's back button rather than state this
  application has to model. It also keeps hls.js — 575 kB, more than a hundred times
  the app code — off the library page entirely, since only the watch page imports it.

- **The filesystem is the source of truth**, as it is for everything else that
  survives a restart ([ADR-0003](0003-filesystem-as-the-store.md)). A video is
  listed when `hls-dir/<videoId>/master.m3u8` exists. The catalogue never touches
  the job repository, so listing keeps working during a Redis outage for the same
  reason playback does.
- **Only finished videos appear.** In-progress ingestions are already rendered by
  the progress block, which knows things the disk does not — which stage, and why
  it failed. Merging the two would put entries in the library that cannot be
  clicked.
- **Titles come from a sidecar.** `meta.json` is written beside the media at the
  transcoding transition, holding the downloaded filename — the only
  human-readable name this pipeline ever sees, and one that is gone once the
  reaper takes the download directory. It lives *inside* the video's own directory
  so that deleting the media deletes its metadata: one lifetime, no drift.
- **A poster frame is extracted per video.** ffmpeg's `thumbnail` filter picks a representative
  frame rather than whatever a fixed seek lands on, which is what keeps posters from being the
  black frame most files open on. It is written before `master.m3u8` for the same reason the
  sidecar is, so a video is never listed without the thumbnail beside it. Two attempts — five
  seconds in, then from the start — because nothing here knows the source's duration and seeking
  past the end produces no frame at all. A failure is a warning and a placeholder, never a failed
  ingestion.
- **Safe methods stop consuming rate-limit tokens.** `POST` and `GET` share
  `/api/v1/videos`, and the interceptor was registered by path alone, so the
  library would have spent the ingestion budget and started answering `429` after
  five page loads. The bucket exists to bound a side effect; a `GET` has none.

## Consequences

- The documented workflow no longer requires a UUID anywhere.
- The watch page names itself by fetching the whole catalogue and finding one entry,
  because there is no endpoint that answers for a single *video*:
  `GET /api/v1/videos/{videoId}` reports an *ingestion* and 404s for media whose job
  record is gone. The retention window keeps the listing small enough for that to be
  the cheaper answer than a second endpoint; if the library ever outgrows it, that is
  the thing to add.
- **The `retention`/`job-ttl` coupling is now user-visible.** `MediaReaper` has
  always warned that the two are "two halves of one number"; until now a
  disagreement was invisible. It is now a card that 404s when clicked, or a
  playable video missing from the list. That makes drift a bug report rather than
  a silent condition, which is an improvement, but it is a new way for the
  mismatch to show up.
- Videos transcoded before this change have no `meta.json` and are listed by a
  shortened id. They are not backfillable: the download directories they came from
  were reaped, and the magnet's display name was never persisted. Their posters
  *are* backfillable, since ffmpeg can read a frame back out of the ladder itself.
- `web-player` now depends on a third API contract. `StreamJobStatus` values were
  already consumed by name (ADR-0010); the shape of the library entry joins them.
- `meta.json` is reachable through `GET /api/v1/stream/{videoId}/meta.json` as
  `application/octet-stream`, since the playback mapping serves any file in the
  directory. It holds only the title the listing already publishes.
- `HlsMediaTypes` now maps `.jpg`, which is the first non-HLS type it answers for.
  Additive — no existing extension changed — but it is the point at which that class
  stopped being strictly about HLS.
- Still no frontend test runner, so the player half is covered by manual
  verification — load the page, click a card, and ingest one magnet end to end.
