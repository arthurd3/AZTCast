import { icon } from '../ui/icons.js';
import { clock } from './time.js';
import { createSeekBar } from './seekBar.js';
import { createSettingsMenu } from './settingsMenu.js';
import { createShortcuts } from './shortcuts.js';
import { createVolume } from './volume.js';

/**
 * How long the bar waits before hiding itself.
 *
 * video.js uses 2s and Bitmovin 5s; 3s sits between them and is long enough that a viewer
 * reaching for the seek bar does not watch it vanish mid-reach. Touch gets longer, because
 * there is no pointermove to prove someone is still there.
 */
const HIDE_MS = 3000;
const HIDE_TOUCH_MS = 4500;

/** Two taps closer together than this on a touch screen are a double-tap, not two taps. */
const DOUBLE_TAP_MS = 300;

const RATES = [0.5, 0.75, 1, 1.25, 1.5, 2];

/**
 * The player chrome: everything around the <video> element.
 *
 * The native controls attribute stays in the markup and is removed *here*, on the first line
 * of this function. That ordering is the whole progressive-enhancement story: if this bundle
 * fails to load or throws, the page still has a working video player rather than a black
 * rectangle with no way to press play.
 */
export function createControls(root, video, { onFullscreenChange } = {}) {
  video.removeAttribute('controls');
  // Inline playback on iOS. Without it Safari takes the video fullscreen the moment it plays
  // and replaces all of this with its own UI.
  video.setAttribute('playsinline', '');

  const bar = document.createElement('div');
  bar.className = 'controls';

  // ---- Seek ----

  const seek = createSeekBar(video, {
    onSeek: (time) => {
      video.currentTime = time;
    },
  });
  const seekRow = document.createElement('div');
  seekRow.className = 'controls__seek';
  seekRow.appendChild(seek.element);

  // ---- Buttons ----

  const play = controlButton('play', 'Reproduzir', togglePlay);
  const back = controlButton('back10', 'Voltar 10 segundos', () => seekBy(-10));
  const forward = controlButton('forward10', 'Avançar 10 segundos', () => seekBy(10));

  const volume = createVolume(video);

  const time = document.createElement('span');
  time.className = 'controls__time';
  // Announcing every tick would make a screen reader unusable; the seek bar's valuetext is
  // the accessible version of this readout.
  time.setAttribute('aria-hidden', 'true');

  const menu = createSettingsMenu(video, {
    onSelectLevel: (level) => options.onSelectLevel?.(level),
    onSelectSubtitle: (id) => options.onSelectSubtitle?.(id),
    onOpenChange: (open) => {
      if (open) {
        markActive();
      } else {
        scheduleHide();
      }
    },
  });

  const pip = controlButton('pip', 'Picture-in-picture', togglePictureInPicture);
  const fullscreen = controlButton('fullscreen', 'Tela cheia', toggleFullscreen);
  const help = controlButton('keyboard', 'Atalhos de teclado', () => shortcuts.openHelp());

  // Nothing to press if the browser cannot do it. Hidden rather than disabled: a permanently
  // dead button is worse than an absent one.
  if (!document.pictureInPictureEnabled) {
    pip.hidden = true;
  }
  if (!document.fullscreenEnabled) {
    fullscreen.hidden = true;
  }

  const left = document.createElement('div');
  left.className = 'controls__side';
  left.append(play, back, forward, volume.element, time);

  const right = document.createElement('div');
  right.className = 'controls__side';
  right.append(help, menu.element, pip, fullscreen);

  const row = document.createElement('div');
  row.className = 'controls__row';
  row.append(left, right);

  bar.append(seekRow, row);

  // ---- Overlays ----

  const centre = document.createElement('button');
  centre.type = 'button';
  centre.className = 'player__centre';
  centre.setAttribute('aria-label', 'Reproduzir');
  centre.appendChild(icon('play', 'player__centre-icon'));
  centre.addEventListener('click', togglePlay);

  const spinner = document.createElement('div');
  spinner.className = 'player__spinner hidden';
  spinner.setAttribute('role', 'status');
  spinner.setAttribute('aria-label', 'Carregando vídeo');

  // Where quality switches and errors are announced. Not the visual banner — that is the
  // page's job — just the spoken one.
  const live = document.createElement('div');
  live.className = 'visually-hidden';
  live.setAttribute('aria-live', 'polite');

  root.append(centre, spinner, bar, live);

  const options = {};

  // ---- Actions ----

  function togglePlay() {
    if (video.paused) {
      video.play().catch(() => {
        // Autoplay policy, or no source yet. The centre button stays put and the viewer
        // presses it themselves, which is exactly the gesture the policy is asking for.
      });
    } else {
      video.pause();
    }
  }

  function seekBy(delta) {
    if (Number.isFinite(video.duration)) {
      video.currentTime = Math.min(video.duration, Math.max(0, video.currentTime + delta));
    }
  }

  function seekToFraction(fraction) {
    if (Number.isFinite(video.duration)) {
      video.currentTime = video.duration * fraction;
    }
  }

  function adjustVolume(delta) {
    video.muted = false;
    video.volume = Math.min(1, Math.max(0, video.volume + delta));
  }

  function adjustRate(direction) {
    const index = RATES.findIndex((rate) => Math.abs(rate - video.playbackRate) < 0.01);
    const next = Math.min(RATES.length - 1, Math.max(0, (index === -1 ? 2 : index) + direction));
    video.playbackRate = RATES[next];
    announce(`Velocidade ${RATES[next] === 1 ? 'normal' : `${RATES[next]}×`}`);
  }

  async function toggleFullscreen() {
    try {
      if (document.fullscreenElement) {
        await document.exitFullscreen();
      } else {
        // The root, not the video: taking the video alone fullscreen would leave every control
        // in this file behind on the page underneath.
        await root.requestFullscreen();
      }
    } catch {
      // Denied, or unsupported in this context.
    }
  }

  async function togglePictureInPicture() {
    try {
      if (document.pictureInPictureElement) {
        await document.exitPictureInPicture();
      } else {
        await video.requestPictureInPicture();
      }
    } catch {
      // Refused — commonly because the video has no loaded metadata yet.
    }
  }

  function announce(message) {
    live.textContent = message;
  }

  // ---- Auto-hide ----

  let idleTimer = null;
  let pointerInside = false;
  let touchMode = false;

  /**
   * The bar may only hide while something is actually playing and nobody is using it.
   *
   * Two of these clauses are the ones hand-rolled players usually miss:
   *
   *  - **focus**: without it, a viewer tabbing along the bar watches the control they are on
   *    disappear from under them.
   *  - **readyState**: `paused` goes false the instant play() is called, which is long before
   *    a frame exists. Hiding on that alone means the controls vanish during the initial
   *    buffer and while any later stall is being recovered — exactly when a viewer is most
   *    likely to reach for them. HAVE_FUTURE_DATA is the first state where the video is
   *    genuinely playing rather than merely trying to.
   */
  function canHide() {
    return (
      !video.paused &&
      video.readyState >= HTMLMediaElement.HAVE_FUTURE_DATA &&
      !menu.isOpen() &&
      !pointerInside &&
      !bar.contains(document.activeElement) &&
      !video.ended
    );
  }

  function scheduleHide() {
    window.clearTimeout(idleTimer);
    idleTimer = window.setTimeout(
      () => {
        if (canHide()) {
          root.classList.add('is-idle');
        }
      },
      touchMode ? HIDE_TOUCH_MS : HIDE_MS,
    );
  }

  function markActive() {
    root.classList.remove('is-idle');
    scheduleHide();
  }

  root.addEventListener('pointermove', (event) => {
    if (event.pointerType !== 'touch') {
      markActive();
    }
  });
  root.addEventListener('focusin', markActive);
  bar.addEventListener('pointerenter', () => {
    pointerInside = true;
    markActive();
  });
  bar.addEventListener('pointerleave', () => {
    pointerInside = false;
    scheduleHide();
  });

  // ---- Surface gestures ----

  let lastTap = 0;

  video.addEventListener('pointerdown', (event) => {
    if (event.pointerType === 'touch') {
      touchMode = true;
      const now = Date.now();
      if (now - lastTap < DOUBLE_TAP_MS) {
        // Double-tap seeks by side, the convention every mobile player uses.
        const box = video.getBoundingClientRect();
        seekBy(event.clientX - box.left < box.width / 2 ? -10 : 10);
        lastTap = 0;
        return;
      }
      lastTap = now;
      // A single tap shows or hides the controls; it does not play or pause. On a phone the
      // controls are the only way to reach anything else, so revealing them wins.
      root.classList.toggle('is-idle');
      if (!root.classList.contains('is-idle')) {
        scheduleHide();
      }
      return;
    }
    // Mouse: click the picture to play or pause, as on every desktop player.
    togglePlay();
  });

  // ---- Video state ----

  function renderPlayState() {
    const paused = video.paused;
    play.replaceChildren(icon(paused ? 'play' : 'pause'));
    play.setAttribute('aria-label', paused ? 'Reproduzir' : 'Pausar');
    root.classList.toggle('is-paused', paused);
    if (paused) {
      markActive();
    } else {
      scheduleHide();
    }
  }

  function renderTime() {
    const total = Number.isFinite(video.duration) ? video.duration : 0;
    time.textContent = `${clock(video.currentTime)} / ${clock(total)}`;
  }

  video.addEventListener('play', renderPlayState);
  video.addEventListener('pause', renderPlayState);
  video.addEventListener('ended', renderPlayState);
  video.addEventListener('timeupdate', renderTime);
  video.addEventListener('durationchange', renderTime);
  video.addEventListener('loadedmetadata', renderTime);

  video.addEventListener('waiting', () => {
    spinner.classList.remove('hidden');
    // A stall is the moment the viewer wants the controls back.
    markActive();
  });
  for (const settled of ['playing', 'canplay', 'seeked', 'error']) {
    video.addEventListener(settled, () => spinner.classList.add('hidden'));
  }

  document.addEventListener('fullscreenchange', () => {
    const active = document.fullscreenElement === root;
    root.classList.toggle('is-fullscreen', active);
    fullscreen.replaceChildren(icon(active ? 'fullscreenExit' : 'fullscreen'));
    fullscreen.setAttribute('aria-label', active ? 'Sair da tela cheia' : 'Tela cheia');
    onFullscreenChange?.(active);
  });

  const shortcuts = createShortcuts({
    togglePlay,
    seekBy,
    seekToFraction,
    adjustVolume,
    adjustRate,
    toggleMute: () => {
      video.muted = !video.muted;
    },
    toggleFullscreen,
    togglePictureInPicture,
  });

  renderPlayState();
  renderTime();

  return {
    /** Feeds the settings menu the ladder hls.js parsed. */
    setQuality(levels, current, loading) {
      menu.setQuality(levels, current, loading);
    },

    /** Feeds the settings menu the subtitle renditions the stream advertises. */
    setSubtitles(tracks, current) {
      menu.setSubtitles(tracks, current);
    },

    announce,
    markActive,

    /** Lets player.js hand the level-selection callback in after construction. */
    set onSelectLevel(handler) {
      options.onSelectLevel = handler;
    },

    /** Same, for subtitles. */
    set onSelectSubtitle(handler) {
      options.onSelectSubtitle = handler;
    },

    destroy() {
      window.clearTimeout(idleTimer);
      shortcuts.destroy();
    },
  };
}

function controlButton(name, label, onClick) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'btn btn--icon control-btn';
  button.setAttribute('aria-label', label);
  button.appendChild(icon(name));
  button.addEventListener('click', onClick);
  return button;
}
