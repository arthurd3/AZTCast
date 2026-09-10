# Runbook

## Where things live

| Path                             | Contents                                  |
| -------------------------------- | ----------------------------------------- |
| `streaming-api/var/downloads/`   | Raw torrent downloads (local)             |
| `streaming-api/var/hls/`         | Transcoded HLS ladders (local)            |
| `/var/lib/aztcast/` (volume)     | Both of the above, in containers          |

Both are **regenerable caches**. Deleting them loses downloaded media, not
application state.

> Before this refactor these were `./download-torrents/` and `./downloads/hls` —
> two unrelated top-level directories. Anything still under the old paths is
> unreachable and can be deleted.

## Reaching the API from somewhere else

The HTTP surface binds to loopback. Two things have to change together to move it,
and changing only the first is the most likely way to meet a confusing 403:

```yaml
# 1. where it listens
#    compose:  WEB_BIND=0.0.0.0 make up
#    direct:   server.address: 0.0.0.0
# 2. what it answers to
aztcast:
  streaming:
    web:
      allowed-hosts: [localhost, 127.0.0.1, "::1", "[::1]", 192.168.2.112]
```

A request naming a host that is not on that list gets `403` with a
`host-not-allowed` problem document, and the API logs the host it refused:

```
Refused a request for Host 'nas.local' — not in allowed-hosts [localhost, 127.0.0.1, ::1, [::1]]
```

That is the check working. It exists because binding to loopback does not stop a
browser: a page can rebind its own DNS name to `127.0.0.1` and reach a local service
as same-origin, and the `Host` header is the one part of that request the page could
not choose. Setting `allowed-hosts: []` disables it, along with the `Origin` check on
writes that shares the list.

`Origin` is checked on `POST`, `PUT`, `DELETE` and `PATCH` only, and only when the
header is present — `curl` and the smoke test send none and are unaffected.

## Reclaiming disk

**Nothing deletes a finished video.** The HLS ladder under `hls/` stays until
someone deletes it, and there is no window, no sweep and nothing to configure
([ADR-0030](decisions/0030-the-library-is-not-a-cache.md)). If you are looking for
`aztcast.streaming.storage.retention`, it is gone — see the note at the end of this
section.

Deleting is an API call, and the library's card control is the same call:

```bash
curl -X DELETE http://localhost:8000/api/v1/videos/<videoId>
curl -X DELETE 'http://localhost:8000/api/v1/videos/<videoId>?force=true'   # if it is saved
```

It takes the ladder, the raw download, the job record and the magnet claim. That
last one matters: releasing it is what lets the same magnet be added again as a
fresh ingestion rather than being deduplicated onto the id you just deleted.

A **saved** video (a `keep.json` in its HLS directory, written by the library's
marker) answers 409 instead, until the request carries `force=true`. The marker
used to exempt a video from the reaper; with no reaper, that is what it does now.

```bash
# what is saved, and what it costs
find /var/lib/aztcast/hls -name keep.json -printf '%h\n' | xargs -r du -sh
```

`rm -rf` on a video's directories still works and is still safe, but prefer the
endpoint — the shell cannot release the magnet claim, so a video removed that way
cannot be re-ingested until the claim expires:

```bash
docker compose -f deploy/docker-compose.yml exec streaming-api \
  rm -rf /var/lib/aztcast/hls/<videoId> /var/lib/aztcast/downloads/<videoId>
```

### What still gets cleaned up automatically

Only `downloads/` — the raw torrent, which is a second full copy of a video nobody
watches and nothing seeds. A verified transcode discards its own source right away,
so the usual answer is "within minutes of the video becoming playable".

`DownloadReaper` sweeps hourly for the copies that never got that far: a failed
fetch, a crashed encode, a process killed between the two. Its window is
`aztcast.streaming.storage.download-retention` (default 24h), and a directory is
aged by its **newest** file rather than its own mtime, so a download slower than the
window cannot be deleted out from under the client fetching it.

### Running out of space

Ingestion is refused, as `507`, when the downloads volume has less than
`aztcast.streaming.storage.min-free-space` (default 2GB) free. Set it to `0` to
disable the check. Since nothing reclaims space on its own, this is the whole of the
disk-full defence, and it is deliberately a refusal up front rather than a failure
two hours into a download.

```bash
curl -s http://localhost:8000/api/v1/storage
# {"usableBytes":…,"totalBytes":…,"mediaBytes":…,"videoCount":…,"minFreeBytes":…}
```

The library's toolbar shows the same numbers, and turns them red as the floor gets
close. `usableBytes` is `null`, not `0`, when the filesystem could not be read —
and an unreadable filesystem does not refuse ingestions.

> **Upgrading:** `aztcast.streaming.storage.retention` no longer exists. Spring
> ignores unknown keys silently, so a configuration that still sets it starts
> normally and does nothing with it. Replace it with `download-retention`, which
> governs `downloads/` only. `aztcast.streaming.redis.job-ttl` is unchanged and no
> longer has to be kept in step with anything — deleting a video now removes its job
> record directly.

### Keeping media on the host rather than in a volume

The compose stack puts everything in a named volume, `media`. That survives
`docker compose down`, but **`docker compose down -v` takes it** — every video you
have and `providers.db` with them. That is a much bigger loss than it used to be:
videos no longer expire, so the volume is now the permanent home of a library rather
than a week of cache. To keep media somewhere you can see and back up, bind-mount it
instead:

```yaml
# deploy/docker-compose.override.yml
services:
  streaming-api:
    volumes:
      - /srv/aztcast:/var/lib/aztcast
  web-player:
    volumes:
      - /srv/aztcast:/var/lib/aztcast:ro
```

The read-only mount on `web-player` is not optional: nginx needs it to serve segments
through `X-Accel-Redirect`, and it must not be able to write there.

## Torrent throughput

The numbers that govern download speed are under
`aztcast.streaming.torrent.network`, and the startup log prints every one of them:

```
Binding BitTorrent to 192.168.2.112 on enp4s0 — the source address of this host's default route
BitTorrent runtime: port=6891 encryption=PREFER_ENCRYPTED lsd=off pex=on bind=/192.168.2.112
  peers=200/torrent (60 active, 600 global) pending=200 trackerBatch=200 ioQueue=2048
  trackerTimeout=PT8S
```

If a download is slow, read that line first — it distinguishes a tuned runtime from a
defaulted one. Then read the progress line, which carries the two facts a percentage
cannot give you:

```
Progress 43.0% for "Some.Release.1080p.WEB-DL" - 37 peers, 4.82 MiB/s
```

The full magnet URI is one DEBUG line, at the start of the download, rather than on
every tick — it is 1.5 kB of tracker query string and it used to bury everything else.

**Check the bind address first.** It is the first line above, and it names the
interface and the reason. `acceptor-address` is blank by default, which now means "the
source address of this host's default route" rather than "whatever interface the
library enumerates first" — that answer was a Docker bridge on any machine with
containers on it, and a listening socket on a bridge address is one no peer on the
internet can reach. If the line names something like `docker0` or `br-…`, a warning
follows it and `acceptor-address` is the override. Pointing it at a tunnel is also how
torrent traffic is confined to one interface; see
[ADR-0015](decisions/0015-ip-masking-belongs-to-the-network.md).

Few peers is a discovery problem: check the swarm is alive, that `6891/tcp` and
`6891/udp` are published and forwarded (without an inbound port the client is
outbound-only and loses every peer that is also behind a NAT), and that the tracker
list is not stale. This is the one port that is meant to be reachable from the
internet; everything else this stack publishes is bound to `127.0.0.1`
([ADR-0031](decisions/0031-only-the-swarm-faces-outward.md)). The base stack publishes that port on `streaming-api`; under the
VPN overlay it is published on `gluetun` instead, because a container sharing another's
network namespace cannot publish ports of its own — Docker refuses the pair with
*"conflicting options: port publishing and the container type network mode"*. Whether
it is reachable from outside still depends on the router, or on the VPN provider
forwarding one. Many peers and a low rate is a transfer problem: raise
`max-active-peer-connections-per-torrent`, which is the real transfer ceiling —
`max-peer-connections-per-torrent` only bounds established connections, and each one
costs about a megabyte of network buffer, so raise the container memory limit with it.

**Refreshing the tracker list.** `aztcast.streaming.torrent.extra-trackers` is a
snapshot, deliberately not a live fetch — looking one up per ingestion would send a
request timed to what is about to be downloaded. Refresh it by hand:

```bash
curl -s https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt \
  | grep -v '^$' | sed 's/^/        - /'
```

Paste the result over the list in `application.yml`. An empty list disables the
mechanism.

**Removing dead trackers.** The magnet's own trackers are used too, and a public magnet
accumulates hosts that shut down years ago as it is copied between indexers. The library
queries peer sources serially, so each dead host delays the live ones behind it — one
real magnet carried seven, each costing a full timeout on every announce round.
`aztcast.streaming.torrent.dead-trackers` strips them before the magnet is used, matched
on host so one entry covers every port and path it appears with. They show up in the log
as:

```
WARN bt.peer.ScheduledPeerSource : Peer collection finished with exception in peer source:
  TrackerPeerSource {UdpTracker{trackerUrl=http://tracker.example:6969/announce}}
java.util.concurrent.TimeoutException
```

`network.tracker-timeout` (8s) caps how long each one costs; the library leaves it unset,
which means waiting for the socket to give up.

## Redis

Optional, and off unless `aztcast.streaming.redis.enabled` is true (the `docker`
profile turns it on). It holds job state, the magnet→videoId claims that make
ingestion idempotent, and rate-limit buckets. **It holds no media.**

**A Redis outage does not stop playback.** Serving a segment never touches it.
What breaks is job status and deduplication. Expect:

- `/actuator/health` → `DOWN`, `/actuator/health/readiness` → `UP`. That is
  deliberate: the container must not be restarted mid-transcode because a cache
  is unavailable.
- Ingestion keeps working. The rate limiter fails **open** — a protective device
  must not become the outage.
- Recovery is automatic once Redis returns; Lettuce reconnects with a backoff of
  a few seconds.

Useful commands:

```bash
# Every key should have a TTL. Any that does not is a bug.
docker compose -f deploy/docker-compose.yml exec redis sh -c \
  'for k in $(redis-cli --scan); do [ "$(redis-cli TTL "$k")" = "-1" ] && echo "NO TTL: $k"; done'

# A magnet that refuses to re-ingest: its claim outlived its job. Drop the claim.
docker compose -f deploy/docker-compose.yml exec redis \
  redis-cli --scan --pattern 'aztcast:v1:magnet:*'
docker compose -f deploy/docker-compose.yml exec redis redis-cli DEL 'aztcast:v1:magnet:<infohash>'

# A client stuck behind the rate limit.
docker compose -f deploy/docker-compose.yml exec redis redis-cli DEL 'aztcast:v1:rl:<ip>'
```

Jobs interrupted by a restart are failed automatically at startup, with reason
"Interrupted by a service restart" — work does not resume, so the alternative
would be a job reporting DOWNLOADING for the seven days its key lives.

## Exit code 137

SIGKILL — the OOM killer. This has happened in this project before.

ffmpeg's memory lives **outside** the JVM heap, so a container sized only for
the heap dies as soon as an encode starts. The image sets
`-XX:MaxRAMPercentage=50` for that reason, and compose sets a 3 GB limit.
`aztcast.streaming.transcoding.pool.max-size` is how many ffmpeg processes can
run at once; raise it and the memory limit together, never one alone.

Note that `core-size` must equal `max-size` for that setting to mean anything.
`ThreadPoolTaskExecutor` only grows past `core-size` once the queue is **full**, so
`core-size: 1` with a 500-deep queue meant the second thread would have appeared on
the 501st video and never before — two concurrent transcodes were configured and one
was what ran. They are equal now, which also means the memory ceiling is reached at
`max-size` encodes rather than at one.

The swarm shares that budget. Each peer connection holds about a megabyte of network
buffer, so `max-peer-connections-per-torrent` (200) is roughly 200 MB per download,
bounded globally by `max-peer-connections` (600). Raising either of those competes
with ffmpeg for the same 3 GB.

## "Port 8080 already in use"

The API failing to bind shows up as
`Failed to start bean 'webServerStartStop'`.

```bash
make dev-down       # the usual cause: a previous local run is still alive
ss -tlnp | grep 8080
```

`make dev` runs that itself before starting, so this is only needed when
diagnosing by hand or after `make api`, which has no teardown of its own.

It is scoped to this checkout by construction — a process is signalled only when
its working directory is inside the repository *and* its command line names the
API's main class, Maven's launcher, or `web-player/`. When something else holds
the port, `dev-down` prints what it is and exits 2 rather than killing it;
`FORCE_PORTS=1 make dev` overrides. Running it in a second terminal will stop the
`make dev` in the first — one local stack at a time is the intent.

Why a leftover outlives the shell that started it: `spring-boot:run` always forks
the application into a second JVM, and the plugin destroys that JVM from a
shutdown hook. SIGTERM to Maven runs the hook and takes the application with it;
SIGKILL skips it and leaves a JVM on :8080 with no parent. So never `kill -9`
Maven unless you are killing the forked JVM by pid as well.

The same run also holds `aztcast.streaming.torrent.acceptor-port` (6891) and
stays in its swarms, so an orphan costs bandwidth even when it is not blocking
a port.

For the compose stack, the player's host port is `${WEB_PORT:-8000}`:

```bash
WEB_PORT=8100 docker compose -f deploy/docker-compose.yml up -d
```

## A video never becomes READY

```bash
curl -s localhost:8080/api/v1/videos/<videoId> | jq
```

- `DOWNLOADING` for a long time — the magnet may have no seeders. Downloads time
  out after `aztcast.streaming.torrent.download-timeout` (2h).
- `FAILED` with *No video file found* — the torrent contained no file with a
  configured video extension. Add to `aztcast.streaming.torrent.video-extensions`.
- `FAILED` with an ffmpeg message — see
  [troubleshooting-hls.md](troubleshooting-hls.md).

Set `logging.level.bt=DEBUG` for swarm and tracker detail. The default is `WARN`;
it used to be `ERROR`, which hid exactly the diagnostics needed here.

## Health

```bash
curl -s localhost:8080/actuator/health | jq
```

The `ffmpeg` component probes the **configured encoder**, not just the binary. A
`DOWN` with *encoder not available in this ffmpeg build* means the image or host
has an ffmpeg without `libx264` — see the troubleshooting guide.

## Metrics

`/actuator/prometheus`, in the standard scrape format. **Not exposed through
nginx** — the stack is unauthenticated, and metrics leak more about a system
than a health check does. A scraper reaches it inside the compose network at
`http://streaming-api:8080/actuator/prometheus`.

Beyond the Micrometer defaults:

| Metric | Answers |
| --- | --- |
| `aztcast_transcode_seconds{outcome,rungs}` | How long a full ladder takes. Tagged by outcome so a fast failure is not read as a fast success. |
| `aztcast_ingestion_completed_total{outcome}` | How many ingestions succeed versus fail. |
| `aztcast_ingestion_deduplicated_total` | Whether magnet deduplication is doing anything — a silent optimisation that stops working looks identical to one that works. |

**Segment egress is not in these metrics**, and that is expected: nginx serves
those bytes, so they never reach the JVM. They are in the nginx media log
instead, which records size, duration and whether the offload acted:

```bash
docker compose -f deploy/docker-compose.yml exec web-player tail -f /var/log/nginx/media.log
# 172.31.0.1 "GET /api/v1/stream/<id>/1080p_000.m4s HTTP/1.1" 200 2156010 rt=0.013 served_by=nginx-sendfile
```

`served_by=nginx-sendfile` is the check that matters. If it is ever missing, the
offload silently stopped and every segment is going back through Tomcat.

## Verifying a deployment

```bash
./scripts/smoke-test.sh http://localhost:8080
```

Checks health, OpenAPI, the 404 and validation paths, and two path-traversal
attempts.
