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
    "readyAt": "2026-09-08T22:38:11Z"
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

Not rate limited, and `no-store`: the list changes the moment an ingestion finishes.

## `GET /api/v1/videos/{videoId}`

Progress of an ingestion.

| `status`      | Meaning                                                  |
| ------------- | -------------------------------------------------------- |
| `DOWNLOADING` | Fetching the torrent.                                     |
| `TRANSCODING` | Downloaded; ffmpeg is producing the ladder.               |
| `READY`       | `streamUrl` is present and the video can be played.       |
| `FAILED`      | `failureReason` explains why.                             |

`404` if no job is known for that id — which is not the same as "no such video".
Job state is in memory unless `aztcast.streaming.redis.enabled` is set, and expires
under a TTL when it is, while the media outlives both. To ask what exists rather
than how an ingestion went, use [`GET /api/v1/videos`](#get-apiv1videos). See
[ADR-0009](decisions/0009-redis-for-state-not-for-media.md) and
[ADR-0011](decisions/0011-the-library-replaces-manual-id-entry.md).

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
