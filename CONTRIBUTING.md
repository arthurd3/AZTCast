# Contributing

## Before you start

```bash
./scripts/check-prereqs.sh
```

ffmpeg is a hard dependency, and the *encoder* matters as much as the binary —
see the script's output.

## The loop

```bash
make dev       # run both applications
make dev-down  # stop a run that outlived its terminal
make test      # API tests + ArchUnit rules
make lint      # player lint and format check
```

`make test` must pass before a pull request. It includes the architecture
fitness functions, so a change that crosses a slice boundary fails the build.

## Things that are contracts, not implementation details

Two of these have bitten this project already. Read them before changing either.

1. **`GET /api/v1/stream/{videoId}/**`** — hls.js resolves variant playlists and
   segments relative to the master URL. Changing the shape of either mapping
   breaks every player.

2. **The body of `POST /api/v1/video/download`** — an out-of-repo Python bot
   extracts the videoId from that Portuguese sentence; it is the only place the
   id appears in that response. It is preserved byte for byte and asserted by a
   test that compares raw bytes. Do not "fix" its language, spacing or
   punctuation. See
   [ADR-0006](docs/decisions/0006-deprecate-the-legacy-download-endpoint.md).

## Structure

The backend is organised by pipeline stage, not by layer. Put code in the slice
that owns it; `shared/` is for things every slice may depend on, and nothing in
`shared/config` or `shared/storage` may depend on a slice. `ArchitectureTest`
enforces this.

New configuration goes in `StreamingProperties`, not in a `@Value` field.

## Commits

One logical change each. For restructuring specifically: **move first, edit
second**, in separate commits — Git's rename detection needs a move that does
not also rewrite the file, and a reviewer needs a diff they can read.

## Architecture decisions

Add an ADR under `docs/decisions/` when a change is hard to reverse or when a
future reader would reasonably ask "why is it like this?". Records are immutable:
supersede, don't edit.
