# A provider log, in SQLite

## Status

Accepted

## Context

Nothing recorded where a download came from. The pipeline saw a swarm for hours and kept not
one fact about it — which peers served, what software they ran, whether they were seeding or
still fetching it themselves.

Three things had to be settled before any of it could be recorded.

**What is actually knowable.** Less than the question implies. A BitTorrent peer is an address
and a port; the protocol has no account, no name, no contact detail. The realistic ceiling is
address, port, self-reported client software, how much of the torrent the peer claims to hold,
and when it was seen — plus whatever an address can be *inferred* to mean against a local
database. That is what qBittorrent's peer panel shows, and it is what this records.

**Where the client name lives.** Not where it looks like it lives. `Peer.getPeerId()` is the
obvious route and is always empty for peers found via DHT or a compact tracker response, which
is nearly all of them: the library never writes the remote id back onto the peer object. The
name exists only in the `v` field of an extended handshake, in flight, and nothing in the
library retains it.

**Where to put it.** There was no database. The two stores were the filesystem
([ADR-0003](0003-filesystem-as-the-store.md)) and Redis, which is optional, off by default,
TTL'd, and in the reference deployment runs with persistence off
([ADR-0009](0009-redis-for-state-not-for-media.md)) — so anything written there answering
"who served this" would not survive the week. A `meta.json`-style sidecar would die with the
media it sits beside and could not be queried across videos.

## Decision

A `providers` slice with its own SQLite file, off by default.

- **SQLite, not Redis and not a sidecar.** It is genuinely queryable across videos, it
  outlives both the job TTL and the media retention window, and it is one file on the disk the
  media already uses — no container, nothing to operate. That is ADR-0003's reasoning applied
  to structured data rather than to bytes.

- **Off by default.** It retains addresses. A feature that quietly starts collecting personal
  data because it shipped in an upgrade is not one anyone opted into, so `enabled` is `false`
  and the endpoints 404 until it is not.

- **One row per peer per video, upserted.** A peer in a two-hour download is seen thousands of
  times, and most sightings know less than the last — a discovery has no client name, a
  connection has no piece counts. The merge uses `COALESCE` so a later, emptier sighting cannot
  erase what an earlier one established, and never lets an event that cannot know the role
  blank it.

- **A messaging agent catches the client name.** The supported way to see raw protocol
  messages, and the only way to see the `v` field at all. Best effort by nature: a peer need
  not send an extended handshake, need not include `v`, and can put anything it likes in it.

- **Geolocation is a local file read, or nothing.** No lookup leaves the process. Resolving
  peers against a web service would hand a third party the list of everyone this machine
  downloads from — a worse leak than any this work closes. No database is bundled: MaxMind's
  GeoLite2 needs an account and a licence key, DB-IP Lite and IP2Location LITE do not, and any
  compatible `.mmdb` works. Without one, peers are still recorded, just without a location.

- **Sightings are queued and may be dropped.** The library reports peers from many threads;
  SQLite writes from one and a geolocation lookup is a file read. So a bounded queue sits
  between them and a single thread drains it. It drops on overflow and counts the drops: this
  is an observation *of* a download, and a download must never stall because the log of it fell
  behind.

- **Rows expire.** Thirty days by default. An address is personal data under the LGPD even
  though none of this identifies a person, and "the database makes it cheap to keep" is not a
  retention policy.

## Consequences

- The acquisition slice gained a second port, `PeerObservationSink`, made of plain types. A
  fitness function now confines `bt..` and Guice to `acquisition..` — it was added after a
  network setting typed as the library's own `EncryptionPolicy` put a BitTorrent import into
  `StreamingProperties`, which every slice reads.
- Reaching the peer event bus at all required the runtime restructuring in
  [ADR-0013](0013-an-ingestion-survives-the-page-that-started-it.md)'s wake: `Bt.client()`
  hides its `BtRuntime`, and the event bus lives on it.
- **JDBC autoconfiguration is now excluded application-wide.** A JDBC driver on the classpath
  makes Boot insist on a `spring.datasource` for the whole service, which does not exist — it
  refused to start even with the provider log switched off. SQL here belongs to one slice, and
  the exclusion says so.
- Peer rows and media now expire on *different* windows (30 days against 7), unlike the job
  TTL, which ADR-0011 tied to the media deliberately. That is intended: the record of who
  served a video is useful after the video is gone, and it is the only thing here that is.
- The provider table's own retention is the only limit on it. There is no per-video cap: a
  swarm is hundreds of rows, not thousands, and the reaper is what bounds the file.
