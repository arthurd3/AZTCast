import './styles/main.css';
import { findVideo, masterPlaylistUrl, playerPageUrl } from './api/streamClient.js';
import { createControls } from './player/controls.js';
import { createAmbientGlow } from './player/ambientGlow.js';
import { createHlsPlayer } from './player/hlsPlayer.js';
import { forget, resumePoint, save } from './player/progressStore.js';
import { clock } from './player/time.js';
import { mountCosmos } from './ui/cosmos.js';
import { icon } from './ui/icons.js';
import { createStatusBanner } from './ui/statusBanner.js';
import { createVideoLibrary } from './ui/videoLibrary.js';
import { createVideoMeta } from './ui/videoMeta.js';

/**
 * The watch page. The library links here with ?v=<videoId>.
 *
 * The id lives in the URL rather than in memory so a video has an address: it can be linked,
 * bookmarked and reloaded, and the browser's back button returns to the library on its own.
 */
const videoId = new URLSearchParams(window.location.search).get('v');

/** How often the playhead is written while playing. */
const SAVE_EVERY_MS = 5000;

const videoElement = document.getElementById('video');
const playerElement = document.getElementById('player');
const stageElement = document.querySelector('.player-stage');
const titleElement = document.getElementById('videoTitle');
const resumeElement = document.getElementById('resume');
const railSection = document.getElementById('railSection');

const status = createStatusBanner(document.getElementById('status'));
const meta = createVideoMeta(document.getElementById('videoMeta'));

document.getElementById('backLink').prepend(icon('arrowLeft'));

const cosmos = mountCosmos();
const glow = createAmbientGlow(videoElement, stageElement);

const controls = createControls(playerElement, videoElement, {
  // Fullscreen hands the whole viewport to the video; there is no page left to decorate.
  onFullscreenChange: (active) => (active ? cosmos?.pause() : cosmos?.resume()),
});

const player = createHlsPlayer(videoElement, {
  onManifestParsed: () => {
    controls.setQuality(player.levels(), player.currentLevel(), player.loadLevel());
    meta.render(player);
    status.hide();
    videoElement.play().catch(() => {
      // Autoplay blocked. The centre play button is already showing, which is the prompt.
    });
  },
  onLevelSwitched: (level) => {
    controls.setQuality(player.levels(), player.currentLevel(), level);
    meta.render(player);
    const height = player.levels()[level]?.height;
    if (height) {
      controls.announce(`Qualidade ${height}p`);
    }
  },
  onError: (message, kind) => {
    status.show(message, kind);
    if (kind === 'error') {
      controls.announce(message);
    }
  },
});

controls.onSelectLevel = (level) => {
  player.selectLevel(level);
  controls.setQuality(player.levels(), player.currentLevel(), player.loadLevel());
  meta.render(player);
};

function play() {
  const masterUrl = masterPlaylistUrl(videoId);

  // No preflight fetch. The original GET-ed the whole manifest just to check it existed
  // and threw it away, so hls.js then downloaded it a second time. hls.js reports a
  // missing manifest through its own error events, which the player already handles.
  if (player.usesMediaSource()) {
    player.load(masterUrl);
  } else if (player.supportsNativeHls()) {
    player.loadNative(masterUrl);
    status.hide();
    // No hls.js means no level list; the settings menu drops its quality group and the
    // metadata panel falls back to what the catalogue recorded.
    videoElement.addEventListener(
      'loadedmetadata',
      () => {
        meta.render(null);
        videoElement.play().catch(() => {});
      },
      { once: true },
    );
  } else {
    status.show('HLS não é suportado neste navegador.', 'error');
  }
}

/**
 * Names the page from the catalogue.
 *
 * Deliberately not awaited before playing: the title is decoration, and making playback wait on
 * a second request would add latency to the one thing the viewer came here for.
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
  if (video) {
    meta.describe(video);
    meta.render(player);
  }
}

/**
 * Offers to pick up where this video was left off.
 *
 * An offer, never an automatic seek: dropping someone forty minutes into a video they meant to
 * restart is far more annoying than one extra click.
 */
function offerResume() {
  const point = resumePoint(videoId);
  if (point === null) {
    return;
  }

  const text = document.createElement('span');
  text.className = 'resume__text';
  text.textContent = `Você parou em ${clock(point)}.`;

  const resume = document.createElement('button');
  resume.type = 'button';
  resume.className = 'btn btn--primary btn--sm';
  resume.textContent = `Retomar de ${clock(point)}`;
  resume.addEventListener('click', () => {
    videoElement.currentTime = point;
    videoElement.play().catch(() => {});
    dismiss();
  });

  const restart = document.createElement('button');
  restart.type = 'button';
  restart.className = 'btn btn--ghost btn--sm';
  restart.textContent = 'Começar do início';
  restart.addEventListener('click', () => {
    forget(videoId);
    dismiss();
  });

  function dismiss() {
    resumeElement.classList.add('hidden');
    resumeElement.replaceChildren();
  }

  resumeElement.replaceChildren(text, resume, restart);
  resumeElement.classList.remove('hidden');
}

/** Writes the playhead often enough to be useful and rarely enough to be free. */
function trackProgress() {
  let ticker = null;

  const write = () => save(videoId, videoElement.currentTime, videoElement.duration);

  videoElement.addEventListener('play', () => {
    cosmos?.pause();
    glow.start();
    window.clearInterval(ticker);
    ticker = window.setInterval(write, SAVE_EVERY_MS);
  });

  const settle = () => {
    cosmos?.resume();
    window.clearInterval(ticker);
    ticker = null;
    write();
  };
  videoElement.addEventListener('pause', settle);

  // Played to the end: there is nothing to resume, and leaving the entry would put a
  // full-width bar on the card forever.
  videoElement.addEventListener('ended', () => {
    cosmos?.resume();
    window.clearInterval(ticker);
    forget(videoId);
  });

  // pagehide, not beforeunload: it fires on mobile Safari when the tab is backgrounded, which
  // is where a viewer most often leaves without closing anything.
  window.addEventListener('pagehide', write);
}

/** The strip of other videos below the player. */
function loadRail() {
  const rail = createVideoLibrary(document.getElementById('rail'), {
    exclude: videoId,
    onSelect: (next) => window.location.assign(playerPageUrl(next)),
    onLoad: ({ total }) => railSection.classList.toggle('hidden', total === 0),
  });
  rail.load();
}

if (videoId) {
  status.show('Carregando vídeo…', 'loading');
  play();
  nameIt();
  trackProgress();
  loadRail();
  videoElement.addEventListener('loadedmetadata', offerResume, { once: true });
} else {
  titleElement.textContent = 'Nenhum vídeo escolhido';
  status.show('Escolha um vídeo na biblioteca.', 'error');
  playerElement.classList.add('hidden');
}
