import './styles/main.css';
import { masterPlaylistUrl } from './api/streamClient.js';
import { createHlsPlayer } from './player/hlsPlayer.js';
import { createQualitySelector } from './player/qualitySelector.js';
import { createStatusBanner } from './ui/statusBanner.js';
import { createVideoInfo } from './ui/videoInfo.js';

const videoIdInput = document.getElementById('videoId');
const loadButton = document.getElementById('loadBtn');
const videoElement = document.getElementById('video');
const selectElement = document.getElementById('qualitySelect');

const status = createStatusBanner(document.getElementById('status'));
const videoInfo = createVideoInfo(document.getElementById('videoInfo'));

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

loadButton.addEventListener('click', loadVideo);
videoIdInput.addEventListener('keydown', (event) => {
  if (event.key === 'Enter') {
    loadVideo();
  }
});
