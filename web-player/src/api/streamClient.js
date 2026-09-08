import { API_BASE_URL } from '../config.js';

/** URL of a video's HLS master playlist. hls.js resolves everything else relative to it. */
export function masterPlaylistUrl(videoId) {
  return `${API_BASE_URL}/api/v1/stream/${encodeURIComponent(videoId)}/master.m3u8`;
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
