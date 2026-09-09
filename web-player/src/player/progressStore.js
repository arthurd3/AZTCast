/**
 * Where each video was left off, in localStorage.
 *
 * Purely a client-side convenience: the API has no notion of a viewer, so there is nothing to
 * sync to and nothing is lost if this store is wiped. Every read and write is wrapped, because
 * localStorage throws rather than returning null in private-mode Safari and when a quota is
 * exceeded — and failing to remember a playhead must never break playback.
 *
 * Lives under player/ because a playhead is the player's concept, but the library imports it
 * too, to draw the resume bar on a card. It is a leaf module: it imports nothing, so this does
 * not pull any playback code onto the library page.
 */
const KEY = 'aztcast:v1:progress';

/** Entries older than this are dropped on the next write. The media is reaped on a window too. */
const MAX_AGE_MS = 90 * 24 * 60 * 60 * 1000;

/** Hard cap, so a long-lived browser cannot grow this without bound. */
const MAX_ENTRIES = 300;

/**
 * Below this, the viewer has not really started — offering to resume 4 seconds in is noise.
 * Above the ceiling they have effectively finished, and resuming would drop them on the
 * credits. Both are the conventional thresholds.
 */
const MIN_RESUME_S = 30;
const DONE_FRACTION = 0.95;

function readAll() {
  try {
    const raw = window.localStorage.getItem(KEY);
    const parsed = raw ? JSON.parse(raw) : null;
    // Anything that is not an object — a truncated write, someone else's key — is discarded
    // rather than trusted.
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

function writeAll(entries) {
  try {
    window.localStorage.setItem(KEY, JSON.stringify(entries));
  } catch {
    // Full, or storage is denied. The playhead is a nicety; drop it silently.
  }
}

/** Records where {@code videoId} is now. A zero or unknown duration is not worth storing. */
export function save(videoId, time, duration) {
  if (!videoId || !Number.isFinite(time) || !Number.isFinite(duration) || duration <= 0) {
    return;
  }

  const entries = readAll();
  entries[videoId] = { time, duration, at: Date.now() };
  writeAll(prune(entries));
}

/** Forgets one video — called when it plays through to the end. */
export function forget(videoId) {
  const entries = readAll();
  if (videoId in entries) {
    delete entries[videoId];
    writeAll(entries);
  }
}

/** What was stored for {@code videoId}, or null. */
export function read(videoId) {
  const entry = readAll()[videoId];
  if (!entry || !Number.isFinite(entry.time) || !Number.isFinite(entry.duration)) {
    return null;
  }
  return entry;
}

/**
 * How far through {@code videoId} the viewer got, 0..1, or null if there is nothing worth
 * drawing. Used by the library to put a bar across the bottom of a card.
 */
export function fraction(videoId) {
  const entry = read(videoId);
  if (!entry) {
    return null;
  }
  const value = entry.time / entry.duration;
  return value > 0.01 && value < DONE_FRACTION ? value : null;
}

/**
 * The position to offer resuming from, or null when the offer would be unwelcome.
 * The watch page offers this; it never seeks there on its own.
 */
export function resumePoint(videoId) {
  const entry = read(videoId);
  if (!entry) {
    return null;
  }
  const finished = entry.time / entry.duration >= DONE_FRACTION;
  return entry.time >= MIN_RESUME_S && !finished ? entry.time : null;
}

/** Drops what has expired, then the oldest of whatever is still over the cap. */
function prune(entries) {
  const cutoff = Date.now() - MAX_AGE_MS;
  const live = Object.entries(entries).filter(([, entry]) => (entry?.at ?? 0) > cutoff);
  live.sort((left, right) => right[1].at - left[1].at);
  return Object.fromEntries(live.slice(0, MAX_ENTRIES));
}
