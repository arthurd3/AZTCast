import './styles/main.css';
import { createStreamJob, playerPageUrl } from './api/streamClient.js';
import { pollJob } from './api/jobPoller.js';
import { mountCosmos } from './ui/cosmos.js';
import { createJobProgress } from './ui/jobProgress.js';
import { createLibraryToolbar } from './ui/libraryToolbar.js';
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
const toolbar = createLibraryToolbar(document.getElementById('libraryToolbar'), {
  onChange: (view) => toolbar.setCount(library.filter(view), total),
});
const library = createVideoLibrary(document.getElementById('library'), {
  onSelect: watch,
  emptyAction: {
    label: 'Enviar o primeiro torrent',
    onClick: () => {
      magnetInput.focus();
      magnetInput.scrollIntoView({ block: 'center', behavior: 'smooth' });
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
  jobProgress.render(job);
  status.show('Processando. Isto pode levar alguns minutos.', 'loading');

  activePoll = pollJob(job.videoId, { onUpdate: jobProgress.render });
  try {
    const finished = await activePoll.promise;
    if (finished.status === 'READY') {
      status.hide();
      // Straight to the video the viewer has been waiting minutes for. The library it leaves
      // behind will list it on the way back.
      watch(job.videoId);
    } else {
      status.show(finished.failureReason ?? 'A ingestão falhou.', 'error');
    }
  } catch (error) {
    status.show(error.message, 'error');
  } finally {
    release();
  }
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
