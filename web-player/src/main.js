import './styles/main.css';
import { createStreamJob, masterPlaylistUrl } from './api/streamClient.js';
import { pollJob } from './api/jobPoller.js';
import { createHlsPlayer } from './player/hlsPlayer.js';
import { createQualitySelector } from './player/qualitySelector.js';
import { createJobProgress } from './ui/jobProgress.js';
import { createStatusBanner } from './ui/statusBanner.js';
import { createVideoInfo } from './ui/videoInfo.js';

const videoIdInput = document.getElementById('videoId');
const loadButton = document.getElementById('loadBtn');
const magnetInput = document.getElementById('magnetUrl');
const ingestButton = document.getElementById('ingestBtn');
const videoElement = document.getElementById('video');
const selectElement = document.getElementById('qualitySelect');

const status = createStatusBanner(document.getElementById('status'));
const videoInfo = createVideoInfo(document.getElementById('videoInfo'));
const jobProgress = createJobProgress(document.getElementById('jobProgress'));

/** The poll in flight, so starting a second ingestion does not leave the first one running. */
let activePoll = null;

const player = createHlsPlayer(videoElement, {
  onManifestParsed: () => {
    quality.populate(player.levels());
    videoElement.classList.remove('hidden');
    videoInfo.render(player);
    loadButton.disabled = false;
    status.hide();
    videoElement.play().catch(() => status.show('Clique no vídeo para reproduzir.', 'loading'));
  },
  onLevelSwitched: (level) => {
    quality.syncTo(level);
    videoInfo.render(player);
  },
  onError: (message, kind) => {
    status.show(message, kind);
    // Always give the control back. The original left the button disabled on any fatal
    // error, so the only way to retry was reloading the page.
    loadButton.disabled = false;
  },
});

const quality = createQualitySelector(selectElement, (level) => {
  player.selectLevel(level);
  videoInfo.render(player);
});

function loadVideo() {
  const videoId = videoIdInput.value.trim();
  if (!videoId) {
    status.show('Por favor, digite um ID de vídeo!', 'error');
    return;
  }

  status.show('Carregando vídeo…', 'loading');
  loadButton.disabled = true;
  videoElement.classList.add('hidden');
  quality.hide();
  videoInfo.clear();

  const masterUrl = masterPlaylistUrl(videoId);

  // No preflight fetch. The original GET-ed the whole manifest just to check it existed
  // and threw it away, so hls.js then downloaded it a second time. hls.js reports a
  // missing manifest through its own error events, which the player already handles.
  if (player.usesMediaSource()) {
    player.load(masterUrl);
  } else if (player.supportsNativeHls()) {
    player.loadNative(masterUrl);
    videoElement.classList.remove('hidden');
    loadButton.disabled = false;
    status.show('Usando o player nativo do navegador.', 'success');
    setTimeout(() => status.hide(), 3000);
    videoElement.addEventListener('loadedmetadata', () => videoElement.play().catch(() => {}), {
      once: true,
    });
  } else {
    status.show('HLS não é suportado neste navegador.', 'error');
    loadButton.disabled = false;
  }
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
  // right outcome and needs no special case: the id is filled in and the poll picks it up
  // wherever it happens to be.
  videoIdInput.value = job.videoId;
  jobProgress.render(job);
  status.show('Processando. Isto pode levar alguns minutos.', 'loading');

  activePoll = pollJob(job.videoId, { onUpdate: jobProgress.render });
  try {
    const finished = await activePoll.promise;
    if (finished.status === 'READY') {
      status.hide();
      loadVideo();
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

loadButton.addEventListener('click', loadVideo);
videoIdInput.addEventListener('keydown', (event) => {
  if (event.key === 'Enter') {
    loadVideo();
  }
});
