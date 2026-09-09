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
 * answers for a video that merely exists, and the retention window keeps it small.
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
