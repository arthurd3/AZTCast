import './styles/main.css';
import {
  createStreamJob,
  getStreamJob,
  listActiveJobs,
  playerPageUrl,
} from './api/streamClient.js';
import { forget, recall, remember } from './api/activeJobStore.js';
import { pollJob } from './api/jobPoller.js';
import { mountCosmos } from './ui/cosmos.js';
import { createJobProgress } from './ui/jobProgress.js';
import { createLibraryToolbar } from './ui/libraryToolbar.js';
import { createProviderPeers } from './ui/providerPeers.js';
import { createStatusBanner } from './ui/statusBanner.js';
import { createVideoLibrary } from './ui/videoLibrary.js';

/**
 * The library page: submit a magnet, watch it land, then pick something to play.
 *
 * Note what this file does *not* import — hls.js. Playback lives on player.html and nothing
 * here reaches for it, which is what keeps 575 kB off the page most visits only browse
 * (ADR-0011).
 */
mountCosmos();

const magnetInput = document.getElementById('magnetUrl');
const ingestButton = document.getElementById('ingestBtn');
const magnetHint = document.getElementById('magnetHint');

const status = createStatusBanner(document.getElementById('status'));
const jobProgress = createJobProgress(document.getElementById('jobProgress'));
const providerPeers = createProviderPeers(document.getElementById('providerPeers'));
const toolbar = createLibraryToolbar(document.getElementById('libraryToolbar'), {
  onChange: (view) => toolbar.setCount(library.filter(view), total),
});
const library = createVideoLibrary(document.getElementById('library'), {
  onSelect: watch,
  onError: (message) => status.show(message, 'error'),
  emptyAction: {
    label: 'Enviar o primeiro torrent',
    onClick: () => {
      magnetInput.focus();
      // The reduced-motion check has to happen here. reset.css sets `scroll-behavior: auto`
      // under that query, but a behavior passed to scrollIntoView is an argument, not a style,
      // and the stylesheet never sees it.
      const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      magnetInput.scrollIntoView({ block: 'center', behavior: reduced ? 'auto' : 'smooth' });
    },
  },
  onLoad: ({ total: loaded, shown, failed }) => {
    total = loaded;
    // Nothing to sort through with one video and nothing to search when the load failed.
    if (failed || loaded < 2) {
      toolbar.hide();
      return;
    }
    toolbar.show();
    toolbar.setCount(shown, loaded);
  },
});

/** How many videos the API returned, before the toolbar narrows them. */
let total = 0;

/** The poll in flight, so starting a second ingestion does not leave the first one running. */
let activePoll = null;

const DEFAULT_HINT = magnetHint.textContent;

/**
 * Hands the video over to the watch page.
 *
 * A full navigation rather than swapping the player in below the list: the id then lives in the
 * URL, so a video can be linked and reloaded, and going back is the browser's job rather than
 * something this page has to model.
 */
function watch(videoId) {
  window.location.assign(playerPageUrl(videoId));
}

/**
 * Whether this looks like a magnet link at all.
 *
 * Checked here so an obvious typo costs no request and no rate-limit token — the bucket is
 * five POSTs, and spending one to be told the string is not a URI would be careless. The
 * server validates properly; this only catches what is plainly not a magnet.
 */
function looksLikeMagnet(value) {
  return /^magnet:\?/i.test(value) && /xt=urn:btih:/i.test(value);
}

function invalidate(message) {
  magnetInput.setAttribute('aria-invalid', 'true');
  magnetHint.textContent = message;
  magnetHint.classList.add('field__hint--error');
  magnetInput.focus();
}

function clearInvalid() {
  magnetInput.removeAttribute('aria-invalid');
  magnetHint.textContent = DEFAULT_HINT;
  magnetHint.classList.remove('field__hint--error');
}

async function startIngestion() {
  const magnetUrl = magnetInput.value.trim();
  if (!magnetUrl) {
    invalidate('Cole um link magnet para começar.');
    return;
  }
  if (!looksLikeMagnet(magnetUrl)) {
    invalidate('Isso não parece um link magnet. Ele começa com “magnet:?xt=urn:btih:”.');
    return;
  }

  clearInvalid();
  activePoll?.cancel();
  providerPeers.clear();
  ingestButton.dataset.loading = 'true';
  ingestButton.disabled = true;
  jobProgress.clear();
  status.show('Enviando…', 'loading');

  let job;
  try {
    job = await createStreamJob(magnetUrl);
  } catch (error) {
    // A 429 arrives here with the server's own detail string, which already says how long to
    // wait — more useful than anything this layer could invent.
    status.show(error.message, 'error');
    release();
    return;
  }

  // The API is idempotent per torrent, so this may be a job that already existed. That is the
  // right outcome and needs no special case: the poll picks it up wherever it happens to be, and
  // jobProgress prints the id and how long it has really been running.
  await follow(job, { resumed: false });
}

/**
 * Polls a job to its end, painting it as it goes.
 *
 * `resumed` separates the two ways of arriving here, because they want opposite things. A fresh
 * submission holds the button — the viewer just clicked it and is waiting on this one torrent —
 * and jumps to the video the moment it is playable. A reattached one does neither: nobody asked
 * for anything on this load, so hijacking the page into the player would be an ambush, and
 * disabling the field for the hours a torrent can take would be worse than the bug this fixes.
 */
async function follow(job, { resumed }) {
  // Written before the first poll rather than after it: the point is to survive a reload, and a
  // reload can happen a second from now.
  remember(job.videoId);

  jobProgress.render(job);
  status.show('Processando. Isto pode levar alguns minutos.', 'loading');
  providerPeers.follow(job.videoId);

  activePoll = pollJob(job.videoId, {
    onUpdate: (update) => {
      jobProgress.render(update);
      // The swarm only exists while the torrent is being fetched. Past that the table stands as
      // the record of who served it, without polling for peers that have all disconnected.
      if (update.status !== 'DOWNLOADING') {
        providerPeers.freeze();
      }
    },
  });
  try {
    const finished = await activePoll.promise;
    forget();
    // The swarm is gone once the download is: what is left is a transcode, and then a video.
    providerPeers.clear();
    if (finished.status === 'READY') {
      announceReady(job.videoId, resumed);
    } else {
      status.show(finished.failureReason ?? 'A ingestão falhou.', 'error');
    }
  } catch (error) {
    // The poller only rejects on a 404, i.e. the job record is gone. Nothing left to follow, so
    // stop remembering it rather than greeting the next load with the same dead id.
    forget();
    providerPeers.clear();
    status.show(error.message, 'error');
  } finally {
    if (!resumed) {
      release();
    }
  }
}

function announceReady(videoId, resumed) {
  if (!resumed) {
    status.hide();
    // Straight to the video the viewer has been waiting minutes for. The library it leaves
    // behind will list it on the way back.
    watch(videoId);
    return;
  }
  status.show('O vídeo terminou de processar e já está na biblioteca.', 'success');
  // The listing was read before the transcode landed, so it does not have this video yet.
  library.load();
}

/**
 * Picks an ingestion that is still running back up, after a reload lost the page that started it.
 *
 * Asks the server rather than trusting storage alone: the id is only a hint about which download
 * is *this* browser's, and a job it never heard of is still worth showing — the alternative is
 * the download running invisibly to completion, which is the whole complaint. Nothing here is
 * allowed to throw into page load, so every call is guarded and the worst case is the old
 * behaviour of showing nothing.
 *
 * It never re-POSTs the magnet to recover a lost id. That spends a rate-limit token and is only
 * idempotent per infohash when Redis is enabled — without it, a reload would start the same
 * download a second time.
 */
async function reattach() {
  const remembered = recall();

  let job = null;
  try {
    const active = await listActiveJobs();
    job = active.find((candidate) => candidate.videoId === remembered) ?? active[0] ?? null;
  } catch {
    // Older API, or the request failed. The remembered id is still worth a look.
  }

  if (!job && remembered) {
    try {
      job = await getStreamJob(remembered);
    } catch {
      // A 404 means the record expired or the id was never real.
      forget();
      return;
    }
  }

  if (!job) {
    forget();
    return;
  }

  if (job.status === 'READY' || job.status === 'FAILED') {
    // It finished while the page was away. Say so instead of silently dropping it.
    forget();
    jobProgress.render(job);
    if (job.status === 'READY') {
      announceReady(job.videoId, true);
    } else {
      status.show(job.failureReason ?? 'A ingestão falhou.', 'error');
    }
    return;
  }

  await follow(job, { resumed: true });
}

function release() {
  delete ingestButton.dataset.loading;
  ingestButton.disabled = false;
}

ingestButton.addEventListener('click', startIngestion);
magnetInput.addEventListener('input', clearInvalid);
magnetInput.addEventListener('keydown', (event) => {
  if (event.key === 'Enter') {
    startIngestion();
  }
});

library.load();
reattach();
