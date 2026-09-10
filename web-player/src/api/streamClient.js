import { API_BASE_URL } from '../config.js';

/** URL of a video's HLS master playlist. hls.js resolves everything else relative to it. */
export function masterPlaylistUrl(videoId) {
  return `${API_BASE_URL}/api/v1/stream/${encodeURIComponent(videoId)}/master.m3u8`;
}

/** Where the library sends a viewer to watch {@code videoId}. */
export function playerPageUrl(videoId) {
  return `/player.html?v=${encodeURIComponent(videoId)}`;
}

/**
 * One video from the catalogue, or null if it is not in it.
 *
 * There is no single-video endpoint to call: `GET /api/v1/videos/{id}` reports an ingestion, and
 * 404s for media whose job record is gone — which is most of it. The listing is the only thing that
 * answers for a video that merely exists.
 */
export async function findVideo(videoId) {
  const videos = await listVideos();
  return videos.find((video) => video.videoId === videoId) ?? null;
}

/**
 * Absolute URL of a listed video's poster frame, or null when it has none.
 *
 * Built here rather than used verbatim: the API returns a root-relative path, which breaks the
 * moment VITE_API_BASE_URL points somewhere other than this origin.
 */
export function posterUrl(video) {
  return video.posterUrl ? `${API_BASE_URL}${video.posterUrl}` : null;
}

/** Starts an ingestion from a magnet URI. Resolves to the job, whose videoId is a field. */
export async function createStreamJob(magnetUrl) {
  const response = await fetch(`${API_BASE_URL}/api/v1/videos`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ magnetUrl }),
  });
  return handle(response);
}

/** Current state of an ingestion: DOWNLOADING, TRANSCODING, READY or FAILED. */
export async function getStreamJob(videoId) {
  const response = await fetch(`${API_BASE_URL}/api/v1/videos/${encodeURIComponent(videoId)}`);
  return handle(response);
}

/**
 * Ingestions still downloading or transcoding.
 *
 * The library listing cannot answer this: it is built from what is on disk, so a video appears
 * only once it is finished. Without this endpoint a reload had no way to find a download that was
 * still running, which is exactly what made refreshing the page lose it.
 */
export async function listActiveJobs() {
  const response = await fetch(`${API_BASE_URL}/api/v1/videos/active`);
  return handle(response);
}

/**
 * The peers that served one video: address, port, client software, and where the address looks
 * like it is. Empty when nothing was recorded; 404 when the provider log is switched off, which
 * callers should treat as "not recording" rather than as an error.
 */
export async function listVideoPeers(videoId) {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/videos/${encodeURIComponent(videoId)}/peers`,
  );
  return handle(response);
}

/**
 * Totals, one entry per downloaded video, and one entry per place on the map.
 *
 * One request for the whole provenance page: the three are views of a single aggregate the API
 * computes in SQL, and asking per video would be slower for the same answer. 404s when the
 * provider log is switched off, which callers should report as "not recording" rather than as a
 * failure.
 */
export async function getProviderSummary(videoId) {
  const query = videoId ? `?videoId=${encodeURIComponent(videoId)}` : '';
  const response = await fetch(`${API_BASE_URL}/api/v1/providers/summary${query}`);
  return handle(response);
}

/**
 * Videos whose ladder is on disk and can be played, newest first.
 *
 * Read from the filesystem by the API, not from job state: a video outlives the record of the
 * ingestion that produced it, so this lists things `getStreamJob` would 404 on.
 */
export async function listVideos() {
  const response = await fetch(`${API_BASE_URL}/api/v1/videos`);
  return handle(response);
}

/**
 * Marks a video as one to keep, or stops keeping it.
 *
 * Nothing deletes a video on a schedule, so this is no longer an exemption from anything. What it
 * buys is a stop: deleting a kept video is refused unless the caller says it means it. That, and
 * the Salvos filter, which it always drove.
 *
 * PUT and DELETE on a sub-resource rather than a POST verb, because the flag is a state to arrive
 * at and not an event: calling either twice is the same as calling it once. Resolves to nothing —
 * the API answers 204, and re-reading the listing is the caller's job.
 */
export async function setVideoKept(videoId, kept) {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/videos/${encodeURIComponent(videoId)}/keep`,
    {
      method: kept ? 'PUT' : 'DELETE',
    },
  );
  if (!response.ok) {
    return handle(response);
  }
  return null;
}

/**
 * Deletes a video: its ladder, the torrent it came from, and everything the API remembers of it.
 *
 * The only way media leaves the disk. Nothing expires, so a library that is filling up empties
 * because someone emptied it.
 *
 * `force` is what the Salvos marker costs: without it a kept video answers 409 rather than being
 * deleted. The caller is expected to ask the person again and retry with force, not to send it
 * pre-emptively — a force that is always on is the same as no marker at all.
 *
 * Resolves to nothing; the API answers 204.
 */
export async function deleteVideo(videoId, { force = false } = {}) {
  const query = force ? '?force=true' : '';
  const response = await fetch(
    `${API_BASE_URL}/api/v1/videos/${encodeURIComponent(videoId)}${query}`,
    { method: 'DELETE' },
  );
  if (!response.ok) {
    return handle(response);
  }
  return null;
}

/**
 * How much room the library has left, and what it is already using.
 *
 * Worth showing precisely because nothing prunes itself: the disk is the only limit there is now,
 * so it should be visible before an ingestion is refused at the floor rather than after.
 *
 * Resolves to `{ usableBytes, totalBytes, mediaBytes, videoCount, minFreeBytes }`. The two byte
 * counts about the volume are null when it could not be read — not zero, which would read as a
 * full disk.
 */
export async function getStorage() {
  const response = await fetch(`${API_BASE_URL}/api/v1/storage`);
  return handle(response);
}

/**
 * Asks the API to put a video that stopped working back together.
 *
 * POST rather than PUT, because this is not a state to arrive at: what it costs depends on what is
 * wrong, from rewriting a playlist to fetching the torrent again. Resolves to `{ videoId, action,
 * detail }` — the action is what matters, because two of the four are asynchronous and one of those
 * takes minutes. The id never changes, which is the whole point of repairing rather than
 * re-ingesting: the link a viewer already has keeps working.
 */
export async function repairVideo(videoId) {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/videos/${encodeURIComponent(videoId)}/repair`,
    { method: 'POST' },
  );
  return handle(response);
}

/**
 * Unwraps a response, turning an RFC 9457 problem document into a readable Error.
 * The API returns application/problem+json for every failure.
 */
async function handle(response) {
  if (response.ok) {
    return response.json();
  }
  let message = `Request failed (${response.status})`;
  try {
    const problem = await response.json();
    if (problem.detail) {
      message = problem.detail;
    }
    if (Array.isArray(problem.errors) && problem.errors.length > 0) {
      message = problem.errors.map((e) => `${e.field}: ${e.message}`).join('; ');
    }
  } catch {
    // Not a problem document — keep the status-based message.
  }
  const error = new Error(message);
  error.status = response.status;
  throw error;
}
