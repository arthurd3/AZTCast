import './styles/main.css';
import { createStreamJob, playerPageUrl } from './api/streamClient.js';
import { pollJob } from './api/jobPoller.js';
import { createJobProgress } from './ui/jobProgress.js';
import { createStatusBanner } from './ui/statusBanner.js';
import { createVideoLibrary } from './ui/videoLibrary.js';

const magnetInput = document.getElementById('magnetUrl');
const ingestButton = document.getElementById('ingestBtn');

const status = createStatusBanner(document.getElementById('status'));
const jobProgress = createJobProgress(document.getElementById('jobProgress'));
const library = createVideoLibrary(document.getElementById('library'), { onSelect: watch });

/** The poll in flight, so starting a second ingestion does not leave the first one running. */
let activePoll = null;

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

async function startIngestion() {
  const magnetUrl = magnetInput.value.trim();
  if (!magnetUrl) {
    status.show('Cole um link magnet.', 'error');
    return;
  }

  activePoll?.cancel();
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
    ingestButton.disabled = false;
    return;
  }

  // The API is idempotent per torrent, so this may be a job that already existed. That is the
  // right outcome and needs no special case: the poll picks it up wherever it happens to be, and
  // jobProgress prints the id.
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
    ingestButton.disabled = false;
  }
}

ingestButton.addEventListener('click', startIngestion);
magnetInput.addEventListener('keydown', (event) => {
  if (event.key === 'Enter') {
    startIngestion();
  }
});

library.load();
