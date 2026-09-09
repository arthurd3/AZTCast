# Redis for state and coordination, not for media

## Status

Accepted

Supersedes [ADR-0003](0003-filesystem-as-the-store.md) on job state. The
filesystem remains the store for media; that half of 0003 stands.

## Context

ADR-0003 argued against a store for job state, and the argument deserves to be
quoted rather than paraphrased, because it is a good one:

> Adding a database for a status field would mean a container to run, a schema
> to migrate and a backup story, for data that is worthless once the media is
> gone.

It also listed what that cost bought:

> - **Job state does not survive a restart.**
> - **Only one instance can serve a given media directory; this does not scale
>   horizontally as written.**

Two further problems were not visible when 0003 was written. Ingestion had no
idempotency, so posting the same magnet twice — a retry after a timeout, a
double-clicked button — downloaded and transcoded the same torrent twice. And
`POST /api/v1/videos` is unauthenticated and makes the server download an
arbitrary torrent, with nothing bounding how often.

## Decision

Run Redis, for **state and coordination only**.

| What | Why it needs shared state |
| --- | --- |
| Job records | Survive a restart; be visible to more than one instance |
| Magnet → videoId claim | `SET NX` makes ingestion idempotent per infohash |
| Rate-limit buckets | A per-process bucket bounds one instance, not the service |

And explicitly **not** for media. A value over roughly a megabyte in Redis
displaces thousands of useful keys, stalls the single-threaded event loop for
every other client, and slows replication; a segment is far larger than that.
Segments stay on disk and leave via nginx —
[ADR-0007](0007-nginx-serves-the-bytes.md).

Two caches were considered and rejected, which is worth recording because both
look obviously worthwhile:

- **The master playlist.** A ~300-byte file the OS page cache already serves
  from RAM, so a Redis round trip is *slower* than the read it replaces. Worse,
  its absence is the readiness sentinel, so a cached negative would report a
  ready video as unready. And under ADR-0007 the JVM never reads it anyway.
- **ffprobe results.** Six forks per ingestion, next to a transcode measured in
  minutes. Caching it optimises 0.01% of the work.

Answering 0003's objection directly: there is no schema and no backup story,
because every key has a TTL and every value is reconstructible from the media
and the request that created it. The container is real, and it is now paying for
three things rather than one status field.

**The magnet claim is a registry, not a lock.** A lease is the obvious reach and
it is wrong: `startIngestion` returns in milliseconds while the work it guards
runs for hours, so any lease short enough to be safe expires long before the
encode finishes. Storing the winning `videoId` under the magnet key is both
correct and more useful — the second caller gets the first caller's video
instead of an error.

Keying is by **infohash, not URL**. Two links to one torrent differ in their
`&tr=` and `&dn=` parameters, so deduplicating on the raw string would look
correct and never once fire.

## Consequences

- Job state survives restarts and is shared between instances. `POST` with a
  magnet already ingested returns the existing job rather than repeating hours
  of work.
- **Durable state introduced a failure mode ephemeral state could not have.**
  Work does not resume across a restart, so an interrupted job would otherwise
  claim DOWNLOADING until its key expired — reproducing exactly the complaint
  0003 was written to fix. `InterruptedJobReaper` fails those jobs at startup
  and releases their claims. If work ever becomes resumable, that becomes
  "resume or fail".
- **Redis is optional and off by default.** Without it the service behaves as it
  did before, minus deduplication and rate limiting. Playback is unaffected in
  every case: serving a segment never touches Redis, which is what bounds the
  blast radius of an outage to "cannot see job status".
- With Redis enabled but unreachable: Lettuce is configured
  `REJECT_COMMANDS` so calls fail immediately rather than queueing until a
  timeout — otherwise an outage becomes Tomcat thread-pool exhaustion. The job
  repository falls back to a local mirror, the rate limiter fails **open**, and
  readiness stays UP while the aggregate health reports DOWN. Verified by
  stopping the container.
- Ingestion is rate limited per client. This surfaced that
  `X-Forwarded-For` was being built with `$proxy_add_x_forwarded_for`, which
  *appends* to a client-supplied header — so a caller could choose its own
  bucket. nginx now sets `$remote_addr`.
- An ArchUnit rule confines the Redis driver to `shared` and
  `ingestion.infrastructure`, so "runs without Redis" stays a real configuration
  rather than an aspiration.
