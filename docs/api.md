# API contract

Base path `/api/v1`. Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)
`application/problem+json` documents.

## Ingestion

### `POST /api/v1/videos`

Starts an ingestion from a magnet URI. Returns immediately; the pipeline runs in
the background.

```http
POST /api/v1/videos
Content-Type: application/json

{ "magnetUrl": "magnet:?xt=urn:btih:..." }
```

```http
202 Accepted
Location: /api/v1/videos/5f47b10e-d445-45df-bf17-9e0310c2012b

{
  "videoId": "5f47b10e-d445-45df-bf17-9e0310c2012b",
  "status": "DOWNLOADING",
  "progressPercent": 0,
  "createdAt": "2026-09-08T21:34:38.141Z",
  "updatedAt": "2026-09-08T21:34:38.141Z"
}
```

`400` with a per-field `errors` array if `magnetUrl` is missing or blank.

#### Idempotency

`POST` is idempotent per torrent. Posting a magnet whose ingestion already
exists returns that job — same `videoId`, no second download — rather than
starting a duplicate. Matching is by **infohash**, so the same torrent from two
sources with different tracker lists is recognised as one.

Requires `aztcast.streaming.redis.enabled`. Without it every POST starts a new
ingestion, as before.

### `429 Too Many Requests`

Ingestion is rate limited per client (default: burst of 5, 20/hour sustained).
The response is `application/problem+json` with type
`https://aztcast.dev/problems/rate-limited` and a `Retry-After` header in
seconds. Playback is **not** rate limited, and neither is any safe method — the
bucket bounds the side effect of `POST`, and `GET /api/v1/videos` shares its path.

## `GET /api/v1/videos`

Everything that can be watched, newest first.

```http
200 OK
Cache-Control: no-store

[
  {
    "videoId": "2724a02c-f275-49d2-8389-e76bfeacd4c6",
    "title": "Big.Buck.Bunny.2008.1080p.mkv",
    "streamUrl": "/api/v1/stream/2724a02c-f275-49d2-8389-e76bfeacd4c6/master.m3u8",
    "posterUrl": "/api/v1/stream/2724a02c-f275-49d2-8389-e76bfeacd4c6/poster.jpg",
    "qualities": ["1080p", "720p", "480p", "360p", "240p"],
    "sizeBytes": 13048576,
    "readyAt": "2026-09-08T22:38:11Z",
    "kept": false
  }
]
```

**Read from disk, not from job state**, and the difference is visible in practice: a video whose
job record has expired or was lost to a restart is still listed here while
`GET /api/v1/videos/{videoId}` answers `404` for it. A video appears once its `master.m3u8`
exists — the file the pipeline writes last — and disappears when the reaper deletes its media.

`posterUrl` is **omitted** when no poster frame exists — anything transcoded before posters did,
or a source ffmpeg could not read a frame out of. Clients should draw a placeholder rather than
request it anyway.

`title` is **omitted** when nothing recorded one. It comes from a `meta.json` written beside the
media at transcode time, so anything transcoded before that existed has no name to give; clients
should fall back to the `videoId`.

`qualities` is what was actually produced, not what is configured. The ladder is planned from
the source: no rung is taller than the file that arrived, and when that file is already H.264 the
top rung is a copy of it named for the source's own height — so a 1080p source gives
`["1080p", "720p", …]` and a 720p one simply has no 1080p rung
([ADR-0018](decisions/0018-the-top-rung-is-copied-not-encoded.md)).

`kept` is always present. `true` means the video is exempt from the retention window and will not
be deleted automatically.

Not rate limited, and `no-store`: the list changes the moment an ingestion finishes.

## `PUT /api/v1/videos/{videoId}/keep`

## `DELETE /api/v1/videos/{videoId}/keep`

Marks a video to outlive the retention window, or stops keeping it.

```http
204 No Content
```

A sub-resource with `PUT`/`DELETE` rather than a verb, because the flag is a state to arrive at
rather than an event: calling either twice is the same as calling it once, and `DELETE` on a video
that was never kept is a success. The only failure is a video whose media is already gone:

```http
404 Not Found
Content-Type: application/problem+json

{
  "type": "https://aztcast.dev/problems/video-not-found",
  "title": "Video not found",
  "status": 404,
  "detail": "No video with id 2724a02c-f275-49d2-8389-e76bfeacd4c6"
}
```

Distinct from `job-not-found`, and the distinction is load-bearing: a job expiring while its media
lives is the ordinary state of every older video, so reporting one as the other would tell a client
to retry an ingestion that is not the problem.

Keeping exempts the HLS ladder only. The raw torrent under `downloads/` is reaped on schedule
either way ([ADR-0019](decisions/0019-kept-videos-outlive-the-retention-window.md)).

**Not rate limited.** The limiter is registered on the exact path `/api/v1/videos`, which does not
match this one. That is deliberate — the limit exists because an ingestion costs hours of CPU and
gigabytes of disk, and writing a marker file costs neither.

## `GET /api/v1/videos/active`

Ingestions still `DOWNLOADING` or `TRANSCODING`, so a client that lost track of one
can find it again.

```http
200 OK
Cache-Control: no-store

[
  {
    "videoId": "5f47b10e-d445-45df-bf17-9e0310c2012b",
    "status": "DOWNLOADING",
    "progressPercent": 64,
    "createdAt": "2026-09-08T21:34:38.141Z",
    "updatedAt": "2026-09-08T21:36:02.907Z"
  }
]
```

This is the counterpart to [`GET /api/v1/videos`](#get-apiv1videos), which lists only
what is **finished**. Neither one lists both: an in-progress ingestion has nothing to
play, which is why [ADR-0011](decisions/0011-the-library-replaces-manual-id-entry.md)
keeps it out of the library. What that left unsolved is that the `videoId` existed only
in the page that started the ingestion — so a browser refresh abandoned a download that
was still running, with no way back to it. Asking here is that way back.

Recovering an id by re-`POST`ing the magnet is the wrong move: it spends a rate-limit
token, and it is only idempotent per infohash when Redis is enabled — without it, the
retry starts the same download a second time.

Empty after a restart: nothing resumes, and the reaper marks interrupted jobs `FAILED`.

Not rate limited, and `no-store`: the answer changes the moment an ingestion finishes.

## `GET /api/v1/videos/{videoId}`

Progress of an ingestion.

| `status`      | Meaning                                                  |
| ------------- | -------------------------------------------------------- |
| `DOWNLOADING` | Fetching the torrent.                                     |
| `TRANSCODING` | Downloaded; ffmpeg is producing the ladder.               |
| `READY`       | `streamUrl` is present and the video can be played.       |
| `FAILED`      | `failureReason` explains why.                             |

`progressPercent` (0-100) is how much of the **torrent** has arrived, and only that. It
reaches 100 when the download finishes and stays there, which is why a job that failed
while transcoding still says how far the download got.

`transcodePercent` (0-100) is how much of the **encode** is done. It is **absent** until
transcoding starts — the field is omitted rather than sent as 0, so "not started" and
"0% done" are distinguishable without a third field. Both figures are measured: the swarm
reports pieces, and ffmpeg reports `out_time_us` against the source duration. They are two
fields rather than one reused because they measure different work; see
[ADR-0024](decisions/0024-transcode-progress-is-its-own-figure.md).

`404` if no job is known for that id — which is not the same as "no such video".
Job state is in memory unless `aztcast.streaming.redis.enabled` is set, and expires
under a TTL when it is, while the media outlives both. To ask what exists rather
than how an ingestion went, use [`GET /api/v1/videos`](#get-apiv1videos). See
[ADR-0009](decisions/0009-redis-for-state-not-for-media.md) and
[ADR-0011](decisions/0011-the-library-replaces-manual-id-entry.md).

### `POST /api/v1/videos/{videoId}/repair`

Puts a video that stopped working back together, **under the same id**.

POST rather than PUT: what it costs depends on what is wrong, from rewriting a
playlist to fetching the torrent again.

```http
202 Accepted
Location: /api/v1/videos/29dd7faa-3d34-48e2-abd4-732ed5b9abe5

{
  "videoId": "29dd7faa-3d34-48e2-abd4-732ed5b9abe5",
  "action": "MANIFESTS_REBUILT",
  "detail": "The media was intact and the playlists were not. Rebuilding the manifests, which takes seconds and re-encodes nothing."
}
```

| `action` | Meaning | Cost |
| --- | --- | --- |
| `NOTHING_TO_DO` | The ladder is complete and playable. Answered `200`, no `Location`. | — |
| `MANIFESTS_REBUILT` | Segments are intact; only the playlists were wrong. | seconds |
| `RETRANSCODED` | Segments were missing; the retained download is being transcoded again. | minutes |
| `REFETCHED` | The download was gone too; the torrent is being fetched from the recorded magnet. | a download plus an encode |

Everything but `NOTHING_TO_DO` answers `202` and writes a job under the same
videoId, so a client follows it with
[`GET /api/v1/videos/{videoId}`](#get-apiv1videosvideoid) exactly as it follows an
ingestion.

`404` if there is no video for that id. `422` with
`type: https://aztcast.dev/problems/not-repairable` when the media is incomplete,
the download has been reaped and no magnet was recorded — which is the state of
anything ingested before the sidecar carried one.

Rate limited, unlike `/keep`: at its most expensive this spends a full download and
a full encode. See
[ADR-0026](decisions/0026-verified-before-it-is-published-repairable-after.md).

## Providers

Present only when `aztcast.streaming.providers.enabled` is set. Both endpoints `404`
otherwise, which says "not recording" more clearly than an empty array would.

### `GET /api/v1/videos/{videoId}/peers`

The peers that served one video, most recently seen first.

```http
200 OK
Cache-Control: no-store

[
  {
    "videoId": "5f47b10e-d445-45df-bf17-9e0310c2012b",
    "ipAddress": "185.97.73.178",
    "port": 6881,
    "client": "qBittorrent/5.2.3",
    "countryCode": "BR",
    "country": "Brazil",
    "city": "São Paulo",
    "asn": 28573,
    "network": "Claro NXT Telecomunicacoes Ltda",
    "role": "SEEDER",
    "completePercent": 100,
    "timesConnected": 1,
    "firstSeen": "2026-09-08T21:34:41.010Z",
    "lastSeen": "2026-09-08T21:52:03.884Z"
  }
]
```

**This is the ceiling of what BitTorrent discloses, and it is less than it looks.** A peer
is an address and a port. There is no name, no account and no contact detail, because the
protocol has no such concept.

- `client` is what the remote software volunteers about itself in an extended handshake.
  Frequently absent — many peers never send one — and trivially spoofable. It describes the
  shape of a swarm; it identifies nobody.
- `countryCode`, `country`, `city`, `asn` and `network` are **inferred** from the address
  against a local `.mmdb`, never reported by the peer and never asked of anyone. Absent
  entirely unless a database is configured. Country is usually right and city often is not;
  a peer behind a VPN or carrier NAT resolves to the operator, not to anywhere a person has
  been. `asn` is the most reliable of them, being a routing fact rather than an estimate.
- `role` is `SEEDER`, `LEECHER` or `UNKNOWN`, from the peer's bitfield. `UNKNOWN` is the
  common case: most sightings are discoveries, which say nothing about what a peer holds.
- `timesConnected` separates a peer that actually served bytes from an address a tracker
  merely named, and `bytesDownloaded` says how much it actually sent — the difference
  between a peer being present and a peer being a source. `bytesUploaded` is the same
  figure in reverse.
- `connectedSeconds` is the **longest** connection observed, not the total across
  reconnects: it is sampled while a connection is open, so each sighting reports the
  duration so far and summing them would count the same seconds repeatedly. Dividing
  `bytesDownloaded` by it gives an average rate only when `timesConnected` is 1 — beyond
  that the two figures come from different sessions and the quotient understates.
- `networkKind` is `RESIDENTIAL`, `HOSTING` or `UNKNOWN`, **derived on read** from the ASN
  and operator name, never stored. `HOSTING` means the address looks like a datacenter or a
  VPN exit — a machine, not a household. It is a heuristic against a bundled list, so it is
  a good guess and not a fact; a residential ISP that also sells hosting will be misread.
  No request is made to classify it.
- `capabilities` is what the peer announced before any data moved: `DHT`, `EXT` (BEP-10),
  `FAST` (BEP-6) from the base handshake's reserved bits, and `PEX`/`METADATA` from the
  extended handshake's extension map. Nearly every modern client reports all five.
- `videosServed` is how many of this instance's downloads the same address turned up in.
- `latitude`, `longitude` and `accuracyRadiusKm` carry the same caveat as everywhere else:
  a region, not a point.
- `client` now comes from the peer_id on the base handshake when the extended handshake
  does not supply one. Against a live swarm that took coverage of *connected* peers from
  roughly one in eight to all of them, since every client sends a peer_id and only some
  send an extended handshake.

One row per peer per video, merged across every sighting — not a log line per event. Rows
expire on `aztcast.streaming.providers.retention` (30 days by default): an address is
personal data under the LGPD, and holding one indefinitely because a database makes it easy
is not a decision anyone made.

### `GET /api/v1/peers?limit=200`

The same rows across every video, most recently seen first. `limit` is clamped to 2000.

### `GET /api/v1/providers/summary?videoId=<optional>`

Everything the provenance page draws, in one response: totals, one entry per video, and
one entry per **place**. `videoId` narrows `places` to a single ingestion; `totals` and
`videos` stay whole, since they are the context a selection is made against.

```http
200 OK
Cache-Control: no-store

{
  "totals": { "videos": 3, "peers": 227, "connected": 50,
              "countries": 44, "networks": 113, "bytesDownloaded": 129430112,
              "hostingPeers": 50, "recurringPeers": 96 },
  "videos": [
    { "videoId": "5f47b10e-…", "title": "Big.Buck.Bunny.2008.1080p.mkv",
      "posterUrl": "/api/v1/stream/5f47b10e-…/poster.jpg",
      "readyAt": "2026-09-08T22:38:11Z", "available": true,
      "peerCount": 64, "connectedCount": 7, "seederCount": 7,
      "bytesDownloaded": 129430112,
      "firstSeen": "2026-09-08T22:31:02Z", "lastSeen": "2026-09-08T22:37:44Z" }
  ],
  "places": [
    { "latitude": -33.44, "longitude": -70.65, "city": "Santiago", "country": "Chile",
      "countryCode": "CL", "peerCount": 2, "connectedCount": 1,
      "bytesDownloaded": 50331648, "networks": ["VTR BANDA ANCHA S.A."] }
  ],
  "distributions": {
    "clients":   [ { "label": "qBittorrent/5.2.3", "peers": 30, "bytesDownloaded": 88014848 } ],
    "countries": [ { "label": "United States", "peers": 85, "bytesDownloaded": 41943040 } ],
    "networks":  [ { "label": "Datacamp Limited", "peers": 17, "bytesDownloaded": 37748736 } ]
  },
  "addressPeersSee": "170.246.211.126"
}
```

`distributions` ranks the commonest values of three columns by peer count, eight each. A
ranking, not a breakdown: the tail is cut, so the counts do not sum to `totals.peers`.

`hostingPeers` counts peers on a network that looks like a datacenter or a VPN — see
`networkKind` above for how firmly that should be read. `recurringPeers` counts addresses
seen in more than one download.

**`places` is one entry per location, not per peer.** Many addresses resolve to the same
centroid, so the rows are grouped by coordinate (rounded to ~1 km) in SQL. Forty peers at
one point are one place that served forty times, not forty places — drawing them
separately would stack them invisibly and imply a precision the data does not have.

`accuracyRadiusKm` is present only when the database supplies it; **DB-IP Lite does not**,
while MaxMind's GeoLite2 City does. Clients should say the location is approximate either
way.

**`available: false`** means the video is not in the on-disk catalogue — either the reaper
took it (peer rows live 30 days, media 7, so this is the normal end state of an old
ingestion) or its transcode never finished. It is not an error, and the row is still worth
showing: the record of where something came from outlives the something.

**`addressPeersSee`** is the address remote peers report seeing us as, from the `yourip`
field of their extended handshake. Absent until some peer sends one. It is the only direct
evidence available of whether outbound traffic is masked — everything else on that subject
is configuration describing what ought to happen.

## Playback

### `GET /api/v1/stream/{videoId}/master.m3u8`

The HLS master playlist. `404` until transcoding finishes, which is normal while
a job is in progress — poll the job endpoint rather than retrying blindly.

### `GET /api/v1/stream/{videoId}/{file}`

Variant playlists, segments, and the poster frame (`poster.jpg`).

| Extension | Content-Type                    |
| --------- | ------------------------------- |
| `.m3u8`   | `application/vnd.apple.mpegurl` |
| `.ts`     | `video/mp2t`                    |
| `.m4s`    | `video/iso.segment`             |
| `.mp4`    | `video/mp4`                     |
| `.jpg`    | `image/jpeg`                    |

> **This path shape is frozen.** hls.js resolves variant playlists and segments
> relative to the master URL, so changing either mapping breaks every player.
> Note `{file}` is a *single* path segment: an encoder writing
> `stream_0/playlist.m3u8` would be unreachable through this mapping.

Anything resolving outside the media root is refused and reported as `404`.

### Caching and range

| Response | `Cache-Control` |
| --- | --- |
| Segments and init segments (`.m4s`, `.ts`, `.mp4`) | `public, max-age=31536000, immutable` |
| Playlists (`.m3u8`) | `public, max-age=60, stale-while-revalidate=300, stale-if-error=86400` |
| `404` — still transcoding | `no-store` |

Segments carry an `ETag`, so revalidation is a `304`. `immutable` is literally
true here: a `videoId` is a fresh UUID per ingestion and nothing under it is
rewritten in place. The `404` is explicitly non-cacheable because its absence is
the readiness signal — a cached one would tell a client "not ready" long after
it was.

`Range` is supported on every playback response (`Accept-Ranges: bytes`, `206`,
`416`), whether the bytes come from the API or from nginx.

## Deprecated

### `POST /api/v1/video/download`

Superseded by `POST /api/v1/videos`. Still returns its original plain-text body:

```
Download iniciado. O stream estará disponível em: /api/v1/stream/{videoId}/master.m3u8
```

**That body is preserved byte for byte, accents included**, because an
out-of-repo Python bot extracts the videoId from that sentence — it is the only
place the id appears in the legacy response. A `Location` header, plus
`Deprecation`, `Sunset` and `Link` headers, were added as strictly additive
signals that cannot break a text parser.

Migrating the bot to `POST /api/v1/videos` gets it a JSON `videoId` field and a
job endpoint to poll. See
[ADR-0006](decisions/0006-deprecate-the-legacy-download-endpoint.md).

## Operational endpoints

| Path                        | Purpose                                        |
| --------------------------- | ---------------------------------------------- |
| `GET /actuator/health`      | Liveness and readiness, including an ffmpeg check |
| `GET /v3/api-docs`          | OpenAPI document                                |
| `GET /swagger-ui.html`      | Swagger UI                                      |

## Not implemented

There is **no authentication**. Anyone who can reach `POST /api/v1/videos` can
make the server download arbitrary torrents and consume its disk. Do not expose
this service to an untrusted network.
