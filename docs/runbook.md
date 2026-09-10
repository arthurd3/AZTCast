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

## Reclaiming disk

Media older than `aztcast.streaming.storage.retention` (default 7d) is deleted
automatically — a directory is aged by its **newest** file, not its own mtime, so
an encode running longer than the window cannot have its own output removed
underneath it. The reaper runs hourly.

Retention and `aztcast.streaming.redis.job-ttl` are two halves of one number.
Media outliving its job leaves directories nothing can name; a job outliving its
media reports READY for a video that is gone. **Change them together.**

To reclaim space now, delete a video's directories by hand:

```bash
docker compose -f deploy/docker-compose.yml exec streaming-api \
  rm -rf /var/lib/aztcast/hls/<videoId> /var/lib/aztcast/downloads/<videoId>
```

Both trees are regenerable caches: deleting them costs a re-ingestion, not data.

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
