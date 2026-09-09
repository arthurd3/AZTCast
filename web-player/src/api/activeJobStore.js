/**
 * The ingestion this browser last started, in localStorage.
 *
 * The id was the one thing a reload destroyed. It lived in a variable in main.js, the server
 * offered no way to ask "what is still running", and the library listing only knows about
 * finished videos — so refreshing the page abandoned a download that was otherwise perfectly
 * healthy, with nothing left on screen and no way back to it.
 *
 * Kept next to jobPoller.js because both exist to follow an ingestion; this is the part that
 * has to survive the page. Every access is wrapped for the same reason progressStore.js wraps
 * its own: localStorage throws in private-mode Safari and over quota, and losing the id must
 * degrade to the old behaviour rather than break the page.
 */
const KEY = 'aztcast:v1:activeJob';

/**
 * Forgotten after this. Job records expire server-side on the same window, so a stored id older
 * than one can only 404 — remembering it for longer would mean showing a failure for something
 * that finished a fortnight ago.
 */
const MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;

/** Remembers {@code videoId} as the ingestion to pick back up on the next load. */
export function remember(videoId) {
  if (!videoId) {
    return;
  }
  try {
    window.localStorage.setItem(KEY, JSON.stringify({ videoId, at: Date.now() }));
  } catch {
    // Full, or storage is denied. Reattaching is a convenience; drop it silently.
  }
}

/** The remembered id, or null once it is absent, malformed or stale. */
export function recall() {
  let entry;
  try {
    const raw = window.localStorage.getItem(KEY);
    entry = raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
  if (!entry || typeof entry.videoId !== 'string' || !Number.isFinite(entry.at)) {
    return null;
  }
  return Date.now() - entry.at < MAX_AGE_MS ? entry.videoId : null;
}

/** Called once an ingestion reaches a terminal state, so the next load starts clean. */
export function forget() {
  try {
    window.localStorage.removeItem(KEY);
  } catch {
    // Nothing to do: a stale entry expires on its own.
  }
}
