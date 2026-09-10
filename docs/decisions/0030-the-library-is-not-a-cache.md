# The library is not a cache

## Status

Accepted

Supersedes the reaper half of [ADR-0019](0019-kept-videos-outlive-the-retention-window.md), which
made keeping a video an exemption from a seven-day sweep. The marker survives and so does the
filter built on it; what changes is that there is no longer a sweep to be exempt from. Also amends
[ADR-0003](0003-filesystem-as-the-store.md) and the retention half of
[ADR-0009](0009-redis-for-state-not-for-media.md): the filesystem is still the store, and media
still outlives job state, but the two no longer expire on numbers chosen to match.

## Context

`MediaReaper` deleted any video directory whose newest file was older than
`aztcast.streaming.storage.retention`, default seven days, and it swept both media roots. It was
the only thing in the application that deleted media.

That rule was written for a machine where disk is a commons and the videos are traffic. This runs
on the machine of the person watching. Their disk is theirs, the videos are what they asked for,
and a library that empties itself after a week is a cache wearing a library's name. ADR-0019 saw
half of this and gave people a marker to opt out per video, which left the default backwards: the
thing you had to ask for was to keep what you already had.

The reaper was justified by a pairing. Retention was documented in four places as one number with
`aztcast.streaming.redis.job-ttl`, to be changed together, because media outliving its job "leaves
directories nothing can name" and a job outliving its media "reports READY for a video that is
gone". The first half of that was never true here, and ADR-0019's own consequences say so: the
catalogue reads the disk rather than job state, so a video with no job record is listed, playable,
and already the ordinary state of everything after a restart.

What made the pairing look necessary was that deletion only ever happened on a clock. There was no
`DELETE` endpoint — the API could not delete a video at all, and `application.yml` promised a kept
video "lives until it is deleted deliberately" while the documented way to do that was `rm -rf` on
the media root.

## Decision

The HLS ladder is never deleted automatically. Deleting is something a person does.

- **`DELETE /api/v1/videos/{videoId}` removes the ladder, the raw download, the job record and the
  magnet claim.** All four, at one moment, which is what actually closes the pairing two durations
  were approximating. The claim matters most and is easiest to forget: left behind, re-adding the
  same magnet is deduplicated onto the id of a video that no longer exists, and the caller gets a
  library card that 404s when clicked.

- **The reaper keeps only the half that was always regenerable,** and is renamed `DownloadReaper`
  to stop the name lying. The raw torrent under `downloads/` is a second full copy of a video
  nobody watches, nothing seeds it once the download stops, and repair goes back to the magnet in
  `meta.json` rather than to those bytes.

- **A verified transcode discards its own source immediately.** The sweep is now a backstop for the
  copies that never reach that call — a failed fetch, a crashed encode, a process killed between
  the two — so its window drops from seven days to twenty-four hours. Ordinary downloads are freed
  in minutes rather than a week, which is strictly less disk than the rule this replaces.

- **`keep.json` becomes a guard rather than an exemption.** Deleting a kept video answers 409
  unless the request carries `force=true`. The marker meant "the reaper may not have this"; with no
  reaper it means the only thing left for it to mean — this one was not an accident, ask again.

- **A floor replaces the ceiling.** `storage.min-free-space` (2 GB) refuses an ingestion that would
  start with less than that free, as 507, before anything is claimed or written. Once nothing
  reclaims space on its own, declining work that cannot finish is the only honest thing left to do
  about a full disk. `GET /api/v1/storage` reports the same numbers so the library can show them
  before the refusal rather than after.

## Consequences

- **The disk fills if you let it, and nothing will stop it.** ADR-0019 said this about kept videos
  and it is now true of every video. That is the decision, not a side effect of it: the alternative
  is deleting things nobody asked to delete. What is new is that the failure is visible in advance
  — a number in the toolbar, and a refusal that names the floor — rather than arriving as a
  half-written encode.

- **Repair from a retained download is effectively gone.** `IngestionService.repair` prefers a
  local copy and falls back to re-fetching from the magnet; discarding on success means the
  fallback is now the normal path, so a repair costs a download it used to be able to skip. The
  audio-improvement case is the real loss: `repairSoundLadder` probes the source to notice that
  this host could now produce better audio than was published, and with no source it finds nothing
  to do. Setting `download-retention` and dropping the discard call would buy that back at the
  price of a second copy of every video for the length of the window.

- **`GET /api/v1/videos` grows without bound.** The listing returns everything on disk and the
  library filters it in the browser, which was justified by the window keeping it small. Nothing
  keeps it small now. This is fine at the sizes a person accumulates by hand and will not be fine
  forever; the fix when it comes is a query parameter, not a shorter library.

- **`storage.retention` is gone, not renamed in place.** A configuration that still sets it will
  start and silently ignore it, because Spring binds unknown keys nowhere and complains about
  nothing. That is the one ungraceful edge of this change, and the runbook says so.

- **Deletion has no undo, and now it is the only way media is lost.** The reaper at least took
  things on a schedule people could anticipate. A button takes them at the moment it is pressed,
  which is why it is armed in two clicks in the library and why the marker adds a third.
