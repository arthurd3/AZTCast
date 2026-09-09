import './styles/main.css';
import { findVideo, masterPlaylistUrl } from './api/streamClient.js';
import { createHlsPlayer } from './player/hlsPlayer.js';
import { createQualitySelector } from './player/qualitySelector.js';
import { createStatusBanner } from './ui/statusBanner.js';
import { createVideoInfo } from './ui/videoInfo.js';

/**
 * The watch page. The library links here with ?v=<videoId>.
 *
 * The id lives in the URL rather than in memory so a video has an address: it can be linked,
 * bookmarked and reloaded, and the browser's back button returns to the library on its own.
 */
const videoId = new URLSearchParams(window.location.search).get('v');

const videoElement = document.getElementById('video');
const selectElement = document.getElementById('qualitySelect');
const titleElement = document.getElementById('videoTitle');

const status = createStatusBanner(document.getElementById('status'));
const videoInfo = createVideoInfo(document.getElementById('videoInfo'));

const player = createHlsPlayer(videoElement, {
  onManifestParsed: () => {
    quality.populate(player.levels());
    videoElement.classList.remove('hidden');
    videoInfo.render(player);
    status.hide();
    videoElement.play().catch(() => status.show('Clique no vídeo para reproduzir.', 'loading'));
  },
  onLevelSwitched: (level) => {
    quality.syncTo(level);
    videoInfo.render(player);
  },
  onError: (message, kind) => status.show(message, kind),
});

const quality = createQualitySelector(selectElement, (level) => {
  player.selectLevel(level);
  videoInfo.render(player);
});

function play() {
  const masterUrl = masterPlaylistUrl(videoId);

  // No preflight fetch. The original GET-ed the whole manifest just to check it existed
  // and threw it away, so hls.js then downloaded it a second time. hls.js reports a
  // missing manifest through its own error events, which the player already handles.
  if (player.usesMediaSource()) {
    player.load(masterUrl);
  } else if (player.supportsNativeHls()) {
    player.loadNative(masterUrl);
    videoElement.classList.remove('hidden');
    status.show('Usando o player nativo do navegador.', 'success');
    setTimeout(() => status.hide(), 3000);
    videoElement.addEventListener('loadedmetadata', () => videoElement.play().catch(() => {}), {
      once: true,
    });
  } else {
    status.show('HLS não é suportado neste navegador.', 'error');
  }
}

/**
 * Names the page from the catalogue.
 *
 * Deliberately not awaited before playing: the title is decoration, and making playback wait on a
 * second request would add latency to the one thing the viewer came here for.
 */
async function nameIt() {
  let video = null;
  try {
    video = await findVideo(videoId);
  } catch {
    // Leave the fallback title. A catalogue that will not answer is not a reason to stop playing.
  }
  const name = video?.title ?? videoId;
  titleElement.textContent = name;
  document.title = `${name} — AZTCast`;
}

if (videoId) {
  status.show('Carregando vídeo…', 'loading');
  play();
  nameIt();
} else {
  titleElement.textContent = 'Nenhum vídeo escolhido';
  status.show('Escolha um vídeo na biblioteca.', 'error');
}
