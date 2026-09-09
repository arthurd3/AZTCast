# AZTCast

Turns a magnet link into an adaptive HLS stream you can watch in the browser.

```
magnet URI ──▶ acquisition ──▶ transcoding ──▶ playback ──▶ nginx ──▶ browser
               (bt library)    (ffmpeg)        (authorises) (sendfile)
                     └──────── ingestion ────────┘
                          (drives and tracks)
```

Playback resolves and authorises; **nginx writes the segment bytes**. A request
for a segment costs the JVM a path resolution and a `stat`, not a worker thread
held for the length of a transfer — see
[ADR-0007](docs/decisions/0007-nginx-serves-the-bytes.md).

| Directory        | What it is                                                     |
| ---------------- | -------------------------------------------------------------- |
| `streaming-api/` | Spring Boot 3.5 / Java 21 API: acquires, transcodes, serves HLS |
| `web-player/`    | Vanilla-JS player built with Vite, using hls.js                 |
| `deploy/`        | docker compose (API, nginx, Redis) and the nginx configuration   |
| `docs/`          | Architecture, API contract, runbook, decision records           |
| `scripts/`       | Prerequisite check, dev runner, smoke test                      |

## Prerequisites

- **JDK 21**
- **Node 20+**
- **ffmpeg** with an H.264 encoder — a hard runtime dependency; the API shells
  out to it for every transcode
- **Redis** — *optional*, and off by default. `docker compose` runs one; a local
  `make dev` does not need it. See [what it costs to run without](#redis).

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

#### Through a VPN

```bash
cp deploy/vpn.env.example deploy/vpn.env      # then fill in your provider's keys
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.vpn.yml up --build
```

An overlay, so the stack above still runs without it. The API joins a
[gluetun](https://github.com/qdm12/gluetun) container's network namespace, so every packet it
sends — DHT's UDP included — leaves through the tunnel, and gluetun's firewall drops anything
that would not. Trackers and peers then see the VPN's address instead of this machine's.

**It is not anonymity.** The VPN provider still sees the traffic, and anyone who can compel or
compromise them is back where they started. What it does is real and it is also all it does.
Because the API shares the tunnel's namespace, a dropped VPN takes the API offline rather than
falling back to the open internet — the safe failure, and the reason for doing it this way
([ADR-0015](docs/decisions/0015-ip-masking-belongs-to-the-network.md)).

Verify it is working:

```bash
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.vpn.yml \
  exec streaming-api curl -s https://ifconfig.me      # the VPN's address, not yours
```

## Using it

Four pages. `/` is the library: everything already transcoded, newest first, each
with a poster frame and the name of the file it came from. Click one and it opens
on `/player.html?v=<videoId>`, which is where video is watched. `/providers.html` is
where each download came from — a world map of the peers that served it, drawn from data
compiled into the build so the page makes no external request at all. `/diagnostics.html`
is the fourth: it compares what a manifest advertises against what the browser accepts
and what the server actually serves.

The library is read from disk rather than from job state, so a video is listed for
as long as its media exists — a restart that loses every job record does not empty
it. Search and sort act on the listing in the browser, not as a query: the endpoint
returns everything on disk and the retention window keeps that small.

Paste a magnet link on the library page and press **Enviar** to add one. It polls
the job with a backoff and opens the watch page once it is `READY`; a failure shows
its reason rather than a 404 you have to interpret. The download step carries a real
percentage — the swarm reports pieces — while transcoding shows elapsed time only,
because ffmpeg reports nothing this pipeline reads and a bar that stalls is worse
than no bar.

**Reloading the page no longer loses the download.** The library asks
`GET /api/v1/videos/active` on load and picks any ingestion still running back up,
percentage and elapsed time intact
([ADR-0013](docs/decisions/0013-an-ingestion-survives-the-page-that-started-it.md)).
A resumed one does not steal the page: it reports when it is ready rather than
navigating there on its own.

### Who served it

With `aztcast.streaming.providers.enabled` set, every peer seen for a download is recorded to a
SQLite file and shown under the progress track while it runs: address and port, the client
software, **how many bytes it actually sent**, whether it is seeding or still fetching, and how
often it connected. Point `geoip-city-database` and `geoip-asn-database` at any MaxMind-format
`.mmdb` — GeoLite2 needs a free account, DB-IP Lite and IP2Location LITE do not — and each
address also resolves to a country, a city and a network operator, read from that local file.

**`/providers.html` is where it all comes back.** Totals, a world map of every place that
served something, and one row per video that opens into its peer table. The map has no tile
layer: country outlines are bundled, so nothing is fetched from anyone and the page works
offline ([ADR-0016](docs/decisions/0016-the-provenance-map-is-drawn-offline.md)). Positions
are city-level estimates drawn as areas, never points — GeoIP cannot locate a street, and a
map that could zoom to one would be lying.

It also ranks who is serving you — by client, country and network — and marks each peer that sits
on a **probable datacenter or VPN** rather than a home connection, or that has turned up in more
than one of your downloads. Both are read from the operator name already stored, so neither costs a
request.

The page reports **the address peers say they see you as**, taken from their handshakes. That is the
only direct evidence there is that the VPN above is actually masking anything.

It is **off by default**, and worth knowing why before switching it on: peer addresses are
personal data under the LGPD, so rows expire after 30 days and nothing about a lookup leaves
this machine. It is also worth knowing the ceiling — BitTorrent exposes an address, a port and
a self-reported client string, and nothing that names a person
([ADR-0014](docs/decisions/0014-a-provider-log-in-sqlite.md)).

The player's controls are the application's own, not the browser's
([ADR-0012](docs/decisions/0012-custom-player-controls.md)): buffered ranges are
drawn on the seek bar, the quality ladder and playback speed live in one menu, and
where you stopped is remembered per video in `localStorage` and offered back — never
seeked to on its own.

Keyboard, on the watch page. `?` shows the same list in the player.

| | |
| --- | --- |
| `Espaço` `K` | reproduzir / pausar |
| `J` `L` | −10 s / +10 s |
| `←` `→` | −5 s / +5 s |
| `↑` `↓` | volume |
| `0`–`9` | saltar para 0%…90% |
| `<` `>` | velocidade |
| `M` `F` `P` | mudo, tela cheia, picture-in-picture |

Same thing over HTTP, if you would rather:

```bash
# start an ingestion — idempotent per torrent, so a retry returns the same job
curl -X POST localhost:8080/api/v1/videos \
  -H 'Content-Type: application/json' \
  -d '{"magnetUrl":"magnet:?xt=urn:btih:..."}'

# poll until READY, with a backoff — not in a tight loop
curl -s localhost:8080/api/v1/videos/<videoId>
```

Ingestion is rate limited per client (default: burst of 5, 20/hour). Playback is
not — a player pulls dozens of segments a minute.

Full contract: [docs/api.md](docs/api.md). Interactive: `/swagger-ui.html`.

## Redis

Optional. It holds job state, the magnet→videoId claims that make ingestion
idempotent, and rate-limit counters. **It holds no media** — a value over roughly
a megabyte displaces thousands of useful keys and stalls Redis's single event
loop for every other client, and a segment is far larger than that
([ADR-0009](docs/decisions/0009-redis-for-state-not-for-media.md)).

Without it (`aztcast.streaming.redis.enabled=false`, the default) the service
behaves as it always did: job state is per-instance and lost on restart,
duplicate ingestions are not detected, and ingestion is not rate limited.

**Playback never touches Redis in either case.** Serving a segment is a path
resolution and a `stat`, so a Redis outage means "job status is unavailable",
not "nobody can watch anything". Verified by stopping the container — readiness
stays `UP` so nothing restarts mid-transcode, while `/actuator/health` reports
`DOWN` so operators can see it.

## Developing

```bash
make test         # unit tests, @WebMvcTest slices and the ArchUnit rules
make lint         # eslint + prettier on the player
make build        # both applications
./scripts/smoke-test.sh
```

`make test` fails on architectural erosion, not just on broken behaviour:
`ArchitectureTest` holds twelve ArchUnit rules, each encoding a defect this
codebase actually had.

Two suites need more than a JVM and skip cleanly without it, so the whole thing
stays green on a bare machine:

| Suite | Needs | Why it cannot be faked |
| --- | --- | --- |
| `RealFfmpegLadderTest` | ffmpeg | The defects it guards — an unreachable filename layout, a codec string that does not match the bytes — are invisible to any assertion about the *shape* of a command |
| `RedisIngestionStateIntegrationTest` | Docker | `SET NX` settling a race and Lua refill arithmetic are the parts a stub would define away |

`./scripts/smoke-test.sh` checks the API contract against a running instance.
Give it a video and it also checks delivery:

```bash
VIDEO_ID=<uuid> ./scripts/smoke-test.sh http://localhost:8000
```

## Documentation

- [Architecture](docs/architecture.md) — the slices, the ports, and why they are
  where they are
- [API contract](docs/api.md) — including which paths are frozen and why
- [Runbook](docs/runbook.md) — disk, memory, exit code 137, diagnosing a stuck video
- [HLS troubleshooting](docs/troubleshooting-hls.md) — codec and MIME checklist
- [Decision records](docs/decisions/) — MADR, immutable once accepted. The
  recent ones cover the delivery path ([0007](docs/decisions/0007-nginx-serves-the-bytes.md)),
  the encoding ladder ([0008](docs/decisions/0008-cmaf-ladder-in-one-pass.md)),
  Redis ([0009](docs/decisions/0009-redis-for-state-not-for-media.md)) and the
  player driving ingestion ([0010](docs/decisions/0010-the-player-drives-ingestion.md))

## Security

**There is no authentication.** Anyone who can reach `POST /api/v1/videos` can
make this server download arbitrary torrents. Run it on a trusted network only.
Adding authentication is still the most valuable next change.

Rate limiting bounds that, it does not close it: a caller is limited, not
identified. It needs Redis — without it there is no shared counter and so no
honest limit.

Two things that are handled: media older than
`aztcast.streaming.storage.retention` (7d) is deleted automatically, so filling
the disk now takes sustained effort rather than one afternoon; and nginx sets
`X-Forwarded-For` to `$remote_addr` rather than appending to it, so a client
cannot choose its own rate-limit bucket by sending its own header.

Also note this project downloads whatever magnet link it is given; what you
choose to fetch with it is your responsibility.

## Status

`POST /api/v1/video/download` is deprecated but fully preserved, byte for byte,
because an external client parses the videoId out of its plain-text response.
Use `POST /api/v1/videos` instead — see
[ADR-0006](docs/decisions/0006-deprecate-the-legacy-download-endpoint.md).

Known gaps are listed at the end of [docs/architecture.md](docs/architecture.md):
no auth, no resumable work across restarts, and in-memory job state unless
Redis is enabled.
(Range support and sequential transcoding were listed there; neither is still
true, and that section explains why.)

## Licence

Not yet chosen — the repository is therefore "all rights reserved" by default,
which means nobody can legally reuse or contribute to it. Add a `LICENSE` file
to change that.
