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

```bash
du -sh streaming-api/var/*
rm -rf streaming-api/var/downloads/<videoId>     # keep the HLS output
rm -rf streaming-api/var/hls/<videoId>           # remove a video entirely
docker volume rm aztcast_media                   # containers, everything
```

Nothing prunes automatically. A long-running instance will fill its disk.

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
ss -tlnp | grep 8080
```

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

## Verifying a deployment

```bash
./scripts/smoke-test.sh http://localhost:8080
```

Checks health, OpenAPI, the 404 and validation paths, and two path-traversal
attempts.
