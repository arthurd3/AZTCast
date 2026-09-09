import { getStreamJob } from './streamClient.js';

/** Terminal states: once a job reaches one of these it will not change again. */
const TERMINAL = new Set(['READY', 'FAILED']);

const FIRST_DELAY_MS = 1000;
const MAX_DELAY_MS = 15000;

/**
 * Polls a job until it reaches a terminal state.
 *
 * The delay doubles up to a ceiling rather than staying fixed. A transcode runs for minutes,
 * so a 1s poll would issue hundreds of requests to learn nothing — but the first few seconds
 * are exactly when a fast failure shows up, so it should start tight. docs/api.md has asked
 * callers to "poll the job endpoint rather than retrying blindly" since before any client did.
 *
 * Network errors do not end the poll. A dropped connection midway through a two-hour download
 * is not the job failing, and giving up there would be worse than the blind retrying this
 * replaces. A 404 does end it: the job genuinely does not exist.
 *
 * @returns {{promise: Promise, cancel: function}} cancel() stops polling; the promise never settles after it
 */
export function pollJob(videoId, { onUpdate } = {}) {
  let cancelled = false;
  let timer = null;

  const promise = new Promise((resolve, reject) => {
    let delay = FIRST_DELAY_MS;

    const tick = async () => {
      if (cancelled) {
        return;
      }
      try {
        const job = await getStreamJob(videoId);
        if (cancelled) {
          return;
        }
        onUpdate?.(job);
        if (TERMINAL.has(job.status)) {
          resolve(job);
          return;
        }
      } catch (error) {
        if (cancelled) {
          return;
        }
        if (error.status === 404) {
          reject(error);
          return;
        }
        // Transient: keep polling, but back off like any other attempt.
      }
      delay = Math.min(delay * 2, MAX_DELAY_MS);
      timer = setTimeout(tick, delay);
    };

    timer = setTimeout(tick, 0);
  });

  return {
    promise,
    cancel() {
      cancelled = true;
      clearTimeout(timer);
    },
  };
}
