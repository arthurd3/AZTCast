# An ingestion survives the page that started it

## Status

Accepted

Extends, without superseding, the "only finished videos appear" decision in
[ADR-0011](0011-the-library-replaces-manual-id-entry.md).

## Context

Pressing F5 during a download lost it. Not paused, not cancelled — the download
carried on in the API, seeding progress into a job record nothing was reading,
while the page that came back showed an empty ingestion panel and a library that
would not list the video for another twenty minutes.

Four things had to be true at once, and they were:

1. The `videoId` lived in a local variable in `main.js` and in the poller's
   closure. A reload destroys both.
2. Nothing was persisted. The player already used `localStorage` for playheads
   ([ADR-0012](0012-custom-player-controls.md)), so the pattern existed; it had
   simply never been pointed at ingestion.
3. Nothing re-queried on load. The one request the library page made was
   `GET /api/v1/videos`, which reads the **disk** and therefore cannot show
   anything still downloading — by design, per ADR-0011.
4. There was nothing to ask. `StreamJobRepository.findUnfinished()` had existed
   since durable job state arrived, efficiently implemented on both backends, but
   its only caller was `InterruptedJobReaper` at startup. No HTTP mapping reached
   it.

Any one of the four would have been enough on its own.

ADR-0011 is not wrong about this. Merging in-progress ingestions would still put
unclickable entries in the library. What it did not anticipate is that keeping
them out of the listing left the id with **no home at all** outside the tab that
minted it — and the tab is the one thing guaranteed not to survive a refresh.

## Decision

Two endpoints' worth of truth, and a percentage that is measured rather than
invented.

- **`GET /api/v1/videos/active` lists ingestions still in flight.** A separate
  endpoint rather than a flag on the listing, so ADR-0011's rule holds exactly as
  written: the library is still only what can be played. This is the other half —
  what is still happening — and a client that has lost its ids can ask for it.

- **The client asks the server, and only remembers a hint.** The `videoId` goes
  into `localStorage` under `aztcast:v1:activeJob`, but the reattach path treats
  it as a preference among whatever `/active` returns, not as the source of truth.
  A download started in another tab is still worth showing; the alternative is it
  running to completion invisibly, which is the whole complaint.

- **Recovery never re-`POST`s the magnet.** That spends a rate-limit token, and it
  is idempotent per infohash *only* when Redis is enabled — so without Redis the
  recovery path would start the same download a second time. Re-`GET` only.

- **`progressPercent` is the download, and nothing else.** The swarm reports
  pieces, so the number is real; `BtTorrentDownloader` was already computing it
  for a log line and throwing it away. ffmpeg reports nothing this pipeline reads,
  so transcoding shows no bar rather than a fabricated one — the rule the steps
  component was written around, kept, with the one honest exception now available.

- **A resumed ingestion behaves differently from a submitted one.** A fresh
  submission holds the button and jumps to the video when it is ready. A resumed
  one does neither: nobody clicked anything on this load, so navigating away would
  be an ambush, and disabling the input for the hours a torrent can take would be
  worse than the bug being fixed.

## Consequences

- Progress is written to the job repository on every whole-percent advance — at
  most a hundred writes per download, one Redis round trip each when it is
  enabled. Whole points, not every poll, is what keeps that from being a hot loop
  against a once-a-second sampler.
- `StreamJob` gained a component, so its Redis JSON gained a field. Reading a
  record written by an older build works — a missing `int` defaults — and there is
  now a test pinning that, alongside the one pinning the property set.
- The percentage can only move forward, and stops at the moment the download
  finishes. A tick already in flight when that happens is dropped by a status check
  rather than allowed to write `DOWNLOADING` back over `TRANSCODING`.
- `/active` is empty after a restart, and correctly so: nothing resumes, and the
  reaper marks interrupted jobs `FAILED`. A reattaching client then shows the
  failure instead of a download that is not running.
- `videoId` is a UUID, so the literal `/active` segment cannot collide with one.
  Spring ranks a literal above a template regardless of declaration order; a test
  pins it, because the two mappings are one refactor away from swapping places.
- The player takes on a fourth API contract, after the stream mapping, the job
  status values and the library entry shape.
- Still no frontend test runner, so the reattach path is covered by manual
  verification: start a download, reload mid-flight, and watch the panel come back
  with the percentage and elapsed time intact.
