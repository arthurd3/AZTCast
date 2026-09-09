import { icon } from '../ui/icons.js';

const KEY = 'aztcast:v1:volume';

/**
 * Mute toggle and level slider.
 *
 * The level is remembered across videos, because a viewer who turned the volume down did so
 * for a reason and having it snap back to 100% on the next video is the kind of thing people
 * remember about a player. Stored separately from the playhead: different lifetime, and it is
 * one number rather than a map.
 */
export function createVolume(video) {
  const element = document.createElement('div');
  element.className = 'volume';

  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'btn btn--icon control-btn';

  const slider = document.createElement('div');
  slider.className = 'volume__slider';
  slider.tabIndex = 0;
  slider.setAttribute('role', 'slider');
  slider.setAttribute('aria-label', 'Volume');
  slider.setAttribute('aria-valuemin', '0');
  slider.setAttribute('aria-valuemax', '100');

  const track = document.createElement('div');
  track.className = 'volume__track';
  const fill = document.createElement('div');
  fill.className = 'volume__fill';
  track.appendChild(fill);
  slider.appendChild(track);

  element.append(button, slider);

  let dragging = false;

  function render() {
    const level = video.muted ? 0 : video.volume;
    const percent = Math.round(level * 100);

    fill.style.width = `${percent}%`;
    slider.setAttribute('aria-valuenow', String(percent));
    slider.setAttribute('aria-valuetext', video.muted ? 'Mudo' : `Volume ${percent}%`);

    const name = level === 0 ? 'volumeMute' : level < 0.5 ? 'volumeLow' : 'volumeHigh';
    button.replaceChildren(icon(name));
    // The label says what pressing it will do, not what the state is — the convention that
    // makes a toggle usable without sight of the icon.
    button.setAttribute('aria-label', video.muted ? 'Ativar o som' : 'Silenciar');
    button.setAttribute('aria-pressed', String(video.muted));
  }

  function setLevel(level) {
    const clamped = Math.min(1, Math.max(0, level));
    video.volume = clamped;
    // Dragging up from zero is an unmute; nobody expects to have to press the button too.
    video.muted = clamped === 0;
    save(clamped);
  }

  function levelAt(clientX) {
    const box = track.getBoundingClientRect();
    return box.width === 0 ? 0 : (clientX - box.left) / box.width;
  }

  button.addEventListener('click', () => {
    video.muted = !video.muted;
    // Unmuting something that was dragged to silence should make a sound.
    if (!video.muted && video.volume === 0) {
      setLevel(0.5);
    }
  });

  slider.addEventListener('pointerdown', (event) => {
    dragging = true;
    slider.setPointerCapture(event.pointerId);
    setLevel(levelAt(event.clientX));
    event.preventDefault();
  });

  slider.addEventListener('pointermove', (event) => {
    if (dragging) {
      setLevel(levelAt(event.clientX));
    }
  });

  const stop = (event) => {
    if (dragging) {
      dragging = false;
      slider.releasePointerCapture?.(event.pointerId);
    }
  };
  slider.addEventListener('pointerup', stop);
  slider.addEventListener('pointercancel', stop);

  // Stopped from bubbling for the same reason the seek bar does it: the page shortcuts bind
  // the same arrows.
  slider.addEventListener('keydown', (event) => {
    const steps = { ArrowRight: 0.05, ArrowUp: 0.05, ArrowLeft: -0.05, ArrowDown: -0.05 };
    let target;
    if (event.key in steps) {
      target = video.volume + steps[event.key];
    } else if (event.key === 'Home') {
      target = 0;
    } else if (event.key === 'End') {
      target = 1;
    } else {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    setLevel(target);
  });

  video.addEventListener('volumechange', render);

  // Restore before the first paint, so the slider never shows 100% and then jumps.
  const stored = load();
  if (stored !== null) {
    video.volume = stored;
    video.muted = stored === 0;
  }
  render();

  return { element, render };
}

function load() {
  try {
    const raw = window.localStorage.getItem(KEY);
    const value = raw === null ? null : Number.parseFloat(raw);
    return value !== null && Number.isFinite(value) && value >= 0 && value <= 1 ? value : null;
  } catch {
    return null;
  }
}

function save(level) {
  try {
    window.localStorage.setItem(KEY, String(level));
  } catch {
    // Storage denied or full. The volume still applies to this session.
  }
}
