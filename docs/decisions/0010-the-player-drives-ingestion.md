# The player drives ingestion

## Status

Accepted

Supersedes one framing decision in
[ADR-0001](0001-monorepo-layout.md), which described `web-player` as "the
playback half of the product, not 'the frontend of' the backend — **it never
calls the ingestion endpoint at all**."

## Context

That framing produced a real workflow: `curl` a magnet to
`POST /api/v1/videos`, `curl` the job endpoint until it says `READY`, then paste
the `videoId` into the player by hand. Every step is a copy-paste between two
tools.

Meanwhile `createStreamJob()` and `getStreamJob()` sat in
`src/api/streamClient.js`, correct and imported by nobody. `docs/api.md` advised
callers to "poll the job endpoint rather than retrying blindly" — advice no
client in the repository followed, because no client existed.

## Decision

The player submits magnets and polls for progress.

`pollJob` backs off from 1s, doubling to a 15s ceiling. Two details in it are
load-bearing:

- **A network error does not end the poll.** A dropped connection halfway
  through a two-hour download is not the job failing, and giving up there would
  be worse than the blind retrying this replaces. A `404` *does* end it — the
  job genuinely is not there.
- **Nothing special is done when the API returns an existing job.** `POST` is
  idempotent per torrent ([ADR-0009](0009-redis-for-state-not-for-media.md)), so
  submitting a magnet already in flight returns the running job and the poll
  simply picks it up wherever it is. That is the desired behaviour, not a case
  to detect.

`videoId` entry stays. It is how you replay something ingested earlier, and it
is what the diagnostics page links to.

## Consequences

- The documented workflow no longer requires `curl`.
- **`web-player` now depends on the ingestion contract**, not just the playback
  one. `StreamJobStatus` values are consumed by name in `jobProgress.js`;
  renaming one breaks the UI silently, which the frozen-path warning in
  `docs/api.md` should now be read as covering too.
- The failure path is visible: a job that fails shows its `failureReason` rather
  than a 404 the user has to interpret. This matters more since job state became
  durable — an interrupted job now reports why it stopped instead of vanishing.
- Progress is rendered from DOM nodes, not `innerHTML`. `failureReason` is
  server-supplied and can contain ffmpeg or tracker output.
- Still no frontend test runner, so this is covered by manual verification
  (submit → poll → play, and submit → restart → failure) rather than by tests.
  That gap is unchanged by this ADR but is now guarding more logic.
