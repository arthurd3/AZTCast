# nginx serves the bytes; the API authorises

## Status

Accepted

## Context

Every HLS byte left the JVM. `PlaybackController` returned a
`ResponseEntity<Resource>` and Tomcat copied the file to the socket, so a Tomcat
worker thread was occupied for the full duration of each segment transfer — for
content that is a static file on a disk nginx was already mounted next to.

Nothing carried a caching header. Not `Cache-Control`, not `ETag`, not
`Last-Modified`; a repo-wide grep found none of them. A player re-fetched every
segment in full on every viewing, and no intermediary could help, because the
origin never said anything was cacheable. This is the unusual case where the
content is *provably* immutable — a `videoId` is a fresh UUID per ingestion and
nothing under it is ever rewritten in place — and the origin was saying nothing.

## Decision

Split authorisation from transfer. The API resolves and containment-checks the
asset, then answers with headers only and an `X-Accel-Redirect` naming an
`internal` nginx location; nginx writes the file with `sendfile`.

`aztcast.streaming.playback.offload-enabled` selects this. It defaults to
**off**, and the `docker` profile turns it on. Off, the controller returns a
`FileSystemResource` exactly as before — that is the mode `mvn spring-boot:run`
runs in, and a default that only works inside Docker is a default that breaks
development.

Three things fall out of the split that are easy to get wrong:

- **The stat still happens in the JVM.** The offload is authorisation-only.
  Skipping the lookup and letting nginx 404 would move the readiness sentinel
  out of the application: a client would get nginx's HTML 404 instead of
  `application/problem+json`, and `GlobalExceptionHandler` would stop seeing the
  miss at all.
- **The `ETag` is byte-identical to the one nginx generates** —
  `"hex(mtime seconds)-hex(size)"`. Two different servers answer depending on
  the flag, so if they spelled the validator differently, flipping it would
  silently invalidate every client cache entry and re-download every segment.
- **`Cache-Control` is set in exactly one place, the API.** It survives nginx's
  internal redirect; this was verified against the running stack rather than
  assumed. Setting it in the nginx location too produced two conflicting
  `Cache-Control` headers on every segment.

There is deliberately **no `proxy_cache`** on the stream route. nginx does not
cache a response carrying `X-Accel-Redirect` — the internal redirect happens
during header processing, before the cache layer — so it would be a permanent
silent MISS. It would also be redundant: there is no remote origin here, the
file on disk *is* the cache, and `open_file_cache` is what makes re-serving it
cheap.

## Consequences

- Segment bytes no longer occupy a Tomcat thread. The JVM's work per segment is
  a path resolution and a `stat`.
- Segments advertise `public, max-age=31536000, immutable`; playlists get a
  short max-age with `stale-while-revalidate`. Revalidation costs a `304`.
- **The API and the nginx config are now coupled.** `internal-prefix` must match
  the `internal` location, and `storage.hls-dir` must match its `alias`. Neither
  side can check the other; a mismatch is a silent 404. Both are asserted by
  `scripts/smoke-test.sh`.
- **The media volume is mounted into the player container**, read-only. Before,
  only `streaming-api` could see it.
- Enabling the flag on a JVM that is reachable directly returns an empty 200,
  because nothing is there to honour the header. Hence the default, and hence
  the profile.
- `immutable` is correct *because* `videoId` is a fresh UUID per ingestion. If
  re-transcoding into an existing `videoId` ever becomes a feature, this becomes
  a correctness bug with a one-year blast radius.
