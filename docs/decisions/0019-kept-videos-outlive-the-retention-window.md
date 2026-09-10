# Kept videos outlive the retention window

## Status

Accepted

Amends [ADR-0003](0003-filesystem-as-the-store.md) and the retention half of
[ADR-0009](0009-redis-for-state-not-for-media.md). The filesystem remains the store; what
changes is that not every file in it is on the same clock.

## Context

`MediaReaper` deletes any video directory whose newest file is older than
`aztcast.streaming.storage.retention`, default seven days. That is the only thing in the
application that deletes media, and it is what stops a long-running instance filling its disk.

It is also indiscriminate. A video someone intends to keep is deleted on exactly the same
schedule as one they watched once, and there was no way to say otherwise short of editing
configuration for the whole instance. The library is described as a library; it behaved as a
cache.

The obstacle is that retention is documented in four places as one number with
`aztcast.streaming.redis.job-ttl`, to be changed together. Two failure modes motivated the
pairing: media outliving its job leaves directories nothing can name, and a job outliving its
media reports `READY` for a video that is gone.

## Decision

A video is kept if its HLS directory contains a `keep.json` marker, and `MediaReaper` skips it.

- **The file's existence is the flag.** Its contents are a timestamp for whoever finds it in a
  backup; nothing reads them. A marker file rather than a field in `meta.json` because the
  reaper is the only thing that has to consult it on every directory of every hourly sweep, and
  `Files.exists` answers that without parsing JSON.

- **A file rather than a row in Redis,** for the same reason the catalogue reads the disk at
  all: Redis is optional, is off by default, expires everything under a TTL and in the
  reference deployment runs with persistence disabled. A "keep" flag that quietly aged out
  would be deleted by the very mechanism it was meant to escape.

- **The marker exempts the HLS ladder only.** The raw torrent under `downloads/` is reaped on
  schedule whether or not the video is kept. It is a second full copy, it is not what anyone
  watches, and nothing seeds it once the client stops — exempting it would double the disk cost
  of every kept video and buy nothing.

- **`PUT` and `DELETE` on `/api/v1/videos/{videoId}/keep`.** A sub-resource rather than a verb,
  because the flag is a state to arrive at: either call is idempotent, and un-keeping something
  never kept is a success. Only a video whose media is already gone is a 404.

## Consequences

- **The retention/TTL pairing is broken, in the safe direction only.** A kept video's job record
  still expires, so it ends up listed with no job behind it. That is already the ordinary state
  of every video after a restart, because the catalogue reads the disk and not job state, and
  the endpoint that reports jobs 404s for it exactly as it does for every other old video. The
  dangerous direction — a job reporting `READY` for media the reaper has taken — remains
  impossible, because nothing extends a job's life. The four comments that state the pairing
  now say this.

- **Disk can grow without bound, by request.** That is the point, and it is the one thing the
  reaper existed to prevent, so it is worth stating plainly: an instance where everything is
  kept will fill its disk, and nothing will stop it. The default is unchanged — new videos are
  not kept — so this is a decision a person makes per video.

- **`keep.json` is deleted with the video it marks.** It lives inside the directory, so an
  un-kept video is reaped whole, marker included, exactly as `meta.json` already was.

- **Nothing keeps a video automatically.** Watch history, resume position and ingestion source
  were all considered as signals and rejected: a heuristic that keeps the wrong things is worse
  than a button, because it fails silently and in the direction of filling the disk.
