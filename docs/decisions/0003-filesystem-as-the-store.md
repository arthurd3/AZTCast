# The filesystem is the store; job state is in memory

## Status

Accepted

## Context

The service has no database. Downloaded media and HLS output are files; the only
other state is how far an ingestion has progressed.

Before this change nothing tracked progress at all: the API returned a videoId
and forgot it. A caller could not distinguish "still transcoding" from "failed
twenty minutes ago" — both looked like a 404 on the master playlist — so the only
strategy was to retry forever.

## Decision

Keep the filesystem as the durable store. Track job state in memory behind a
`StreamJobRepository` port, implemented by `InMemoryStreamJobRepository`.

Adding a database for a status field would mean a container to run, a schema to
migrate and a backup story, for data that is worthless once the media is gone.

## Consequences

- No database to operate.
- **Job state does not survive a restart.** A job in flight becomes invisible;
  the media it already produced is still there and still playable.
- Only one instance can serve a given media directory; this does not scale
  horizontally as written.
- Swapping in a persistent implementation is a one-class change, because the
  port exists.
