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

### `GET /api/v1/videos/{videoId}`

Progress of an ingestion.

| `status`      | Meaning                                                  |
| ------------- | -------------------------------------------------------- |
| `DOWNLOADING` | Fetching the torrent.                                     |
| `TRANSCODING` | Downloaded; ffmpeg is producing the ladder.               |
| `READY`       | `streamUrl` is present and the video can be played.       |
| `FAILED`      | `failureReason` explains why.                             |

`404` if no job is known for that id. **Job state is in memory** and does not
survive a restart — see [ADR-0003](decisions/0003-filesystem-as-the-store.md).

## Playback

### `GET /api/v1/stream/{videoId}/master.m3u8`

The HLS master playlist. `404` until transcoding finishes, which is normal while
a job is in progress — poll the job endpoint rather than retrying blindly.

### `GET /api/v1/stream/{videoId}/{file}`

Variant playlists and segments.

| Extension | Content-Type                    |
| --------- | ------------------------------- |
| `.m3u8`   | `application/vnd.apple.mpegurl` |
| `.ts`     | `video/mp2t`                    |
| `.m4s`    | `video/iso.segment`             |
| `.mp4`    | `video/mp4`                     |

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
