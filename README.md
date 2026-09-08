# AZTCast

Turns a magnet link into an adaptive HLS stream you can watch in the browser.

```
magnet URI ──▶ acquisition ──▶ transcoding ──▶ playback ──▶ browser
               (bt library)    (ffmpeg)        (HTTP)
                     └──────── ingestion ────────┘
                          (drives and tracks)
```

| Directory        | What it is                                                     |
| ---------------- | -------------------------------------------------------------- |
| `streaming-api/` | Spring Boot 3.5 / Java 21 API: acquires, transcodes, serves HLS |
| `web-player/`    | Vanilla-JS player built with Vite, using hls.js                 |
| `deploy/`        | docker compose and the nginx reverse proxy                      |
| `docs/`          | Architecture, API contract, runbook, decision records           |
| `scripts/`       | Prerequisite check, dev runner, smoke test                      |

## Prerequisites

- **JDK 21**
- **Node 20+**
- **ffmpeg** with an H.264 encoder — a hard runtime dependency; the API shells
  out to it for every transcode

```bash
./scripts/check-prereqs.sh
```

It also checks that the *configured* encoder exists. Distributions shipping a
patent-free ffmpeg (Fedora's default among them) carry `libopenh264` rather than
`libx264`; the `local` profile already selects it.

## Running it

```bash
make dev          # API on :8080, player on :5173
```

Or separately:

```bash
make api          # cd streaming-api && ./mvnw spring-boot:run
make web          # cd web-player && npm run dev
```

The player reaches the API through Vite's `/api` proxy, so the browser only ever
makes same-origin requests.

### In containers

```bash
make up           # http://localhost:8000
WEB_PORT=8100 docker compose -f deploy/docker-compose.yml up --build -d
```

nginx serves the player and proxies `/api` to the API on the same origin.

## Using it

```bash
# start an ingestion
curl -X POST localhost:8080/api/v1/videos \
  -H 'Content-Type: application/json' \
  -d '{"magnetUrl":"magnet:?xt=urn:btih:..."}'

# poll until READY
curl -s localhost:8080/api/v1/videos/<videoId>
```

Then open the player, paste the `videoId`, and press **Carregar Vídeo**.

Full contract: [docs/api.md](docs/api.md). Interactive: `/swagger-ui.html`.

## Developing

```bash
make test         # unit tests, @WebMvcTest slices and the ArchUnit rules
make lint         # eslint + prettier on the player
make build        # both applications
./scripts/smoke-test.sh
```

`make test` fails on architectural erosion, not just on broken behaviour:
`ArchitectureTest` holds eleven ArchUnit rules, each encoding a defect this
codebase actually had.

## Documentation

- [Architecture](docs/architecture.md) — the slices, the ports, and why they are
  where they are
- [API contract](docs/api.md) — including which paths are frozen and why
- [Runbook](docs/runbook.md) — disk, memory, exit code 137, diagnosing a stuck video
- [HLS troubleshooting](docs/troubleshooting-hls.md) — codec and MIME checklist
- [Decision records](docs/decisions/) — MADR

## Security

**There is no authentication.** Anyone who can reach `POST /api/v1/videos` can
make this server download arbitrary torrents and fill its disk. Run it on a
trusted network only. Adding authentication is the most valuable next change.

Also note this project downloads whatever magnet link it is given; what you
choose to fetch with it is your responsibility.

## Status

`POST /api/v1/video/download` is deprecated but fully preserved, byte for byte,
because an external client parses the videoId out of its plain-text response.
Use `POST /api/v1/videos` instead — see
[ADR-0006](docs/decisions/0006-deprecate-the-legacy-download-endpoint.md).

Known gaps are listed at the end of [docs/architecture.md](docs/architecture.md):
no auth, in-memory job state, no HTTP Range support, sequential transcoding.

## Licence

Not yet chosen — the repository is therefore "all rights reserved" by default,
which means nobody can legally reuse or contribute to it. Add a `LICENSE` file
to change that.
