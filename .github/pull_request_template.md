## What changed

<!-- One or two sentences. -->

## Why

<!-- The problem this solves. -->

## Checklist

- [ ] `make test` passes (unit tests, web slices and the ArchUnit rules)
- [ ] `make lint` passes
- [ ] If the API contract changed, `docs/api.md` is updated
- [ ] If an architectural decision was made, an ADR was added under `docs/decisions/`

## Contract impact

- [ ] `GET /api/v1/stream/{videoId}/**` is unchanged — hls.js resolves variant
      playlists and segments relative to the master URL
- [ ] `POST /api/v1/video/download` still returns its exact legacy body — an
      external client parses the videoId out of that sentence
