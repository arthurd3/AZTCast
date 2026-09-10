import { clock, spoken } from './time.js';

/**
 * The scrub bar.
 *
 * Three things the native control does not give us, and the reasons they are worth the code:
 *
 *  - **Buffered ranges are drawn.** On an adaptive stream the gap between "played" and
 *    "downloaded" is the whole story of whether playback is about to stall, and `video.buffered`
 *    is a list of ranges rather than one number because seeking leaves holes in it.
 *  - **Dragging does not seek.** The playhead only moves on release. Seeking live through a
 *    drag would have hls.js abandon and re-request segments for every intermediate position.
 *  - **It is a real slider to assistive tech**: role, bounds, and a value in words.
 */
export function createSeekBar(video, { onSeek } = {}) {
  const element = document.createElement('div');
  element.className = 'seek';
  element.tabIndex = 0;
  element.setAttribute('role', 'slider');
  element.setAttribute('aria-label', 'Posição do vídeo');
  element.setAttribute('aria-valuemin', '0');

  const track = document.createElement('div');
  track.className = 'seek__track';

  const buffered = document.createElement('div');
  buffered.className = 'seek__buffered';

  const played = document.createElement('div');
  played.className = 'seek__played';

  const thumb = document.createElement('div');
  thumb.className = 'seek__thumb';

  const tooltip = document.createElement('div');
  tooltip.className = 'seek__tooltip';
  tooltip.setAttribute('aria-hidden', 'true');

  track.append(buffered, played, thumb);
  element.append(track, tooltip);

  /** Non-null while a drag is in progress; the fraction the pointer is currently over. */
  let dragging = null;

  function duration() {
    return Number.isFinite(video.duration) && video.duration > 0 ? video.duration : 0;
  }

  /** Where along the bar a client x lands, clamped to it. */
  function fractionAt(clientX) {
    const box = track.getBoundingClientRect();
    if (box.width === 0) {
      return 0;
    }
    return Math.min(1, Math.max(0, (clientX - box.left) / box.width));
  }

  function paint(fraction) {
    const percent = `${(fraction * 100).toFixed(3)}%`;
    played.style.width = percent;
    thumb.style.insetInlineStart = percent;
  }

  function paintBuffered() {
    const total = duration();
    if (total === 0) {
      buffered.replaceChildren();
      return;
    }
    // One node per range. There is normally one, but a viewer who has jumped around leaves
    // holes, and drawing a single bar to the far edge of the last range would claim buffer
    // that is not there.
    const ranges = [];
    for (let index = 0; index < video.buffered.length; index += 1) {
      const start = video.buffered.start(index) / total;
      const end = video.buffered.end(index) / total;
      const span = document.createElement('span');
      span.style.insetInlineStart = `${start * 100}%`;
      span.style.width = `${(end - start) * 100}%`;
      ranges.push(span);
    }
    buffered.replaceChildren(...ranges);
  }

  function describe(seconds) {
    const total = duration();
    return total > 0 ? `${spoken(seconds)} de ${spoken(total)}` : spoken(seconds);
  }

  /** Redraws from the element's own state. Called on timeupdate and progress. */
  function sync() {
    const total = duration();
    element.setAttribute('aria-valuemax', String(Math.floor(total)));
    element.setAttribute('aria-valuenow', String(Math.floor(video.currentTime)));
    element.setAttribute('aria-valuetext', describe(video.currentTime));
    paintBuffered();
    // A drag owns the bar while it lasts, so timeupdate must not fight it.
    if (dragging === null) {
      paint(total > 0 ? video.currentTime / total : 0);
    }
  }

  function showTooltip(fraction) {
    const total = duration();
    if (total === 0) {
      return;
    }
    tooltip.textContent = clock(fraction * total);
    tooltip.style.insetInlineStart = `${fraction * 100}%`;
    element.classList.add('seek--previewing');
  }

  function hideTooltip() {
    if (dragging === null) {
      element.classList.remove('seek--previewing');
    }
  }

  function commit(fraction) {
    const total = duration();
    if (total > 0) {
      onSeek?.(fraction * total);
    }
  }

  // ---- Pointer ----

  element.addEventListener('pointerdown', (event) => {
    if (duration() === 0) {
      return;
    }
    dragging = fractionAt(event.clientX);
    // Capture, so a drag that leaves the bar — or the window — still tracks and still ends.
    element.setPointerCapture(event.pointerId);
    element.classList.add('seek--dragging');
    paint(dragging);
    showTooltip(dragging);
    event.preventDefault();
  });

  element.addEventListener('pointermove', (event) => {
    const fraction = fractionAt(event.clientX);
    if (dragging !== null) {
      dragging = fraction;
      paint(fraction);
    }
    showTooltip(fraction);
  });

  element.addEventListener('pointerleave', hideTooltip);

  function endDrag(event) {
    if (dragging === null) {
      return;
    }
    const fraction = dragging;
    dragging = null;
    element.classList.remove('seek--dragging');
    element.releasePointerCapture?.(event.pointerId);
    commit(fraction);
    hideTooltip();
  }

  element.addEventListener('pointerup', endDrag);
  element.addEventListener('pointercancel', endDrag);

  // ---- Keyboard ----
  //
  // Handled here and stopped from bubbling, so the page-level shortcuts do not also act on the
  // same press and seek twice. The steps are the ones ARIA prescribes for a slider.
  element.addEventListener('keydown', (event) => {
    const total = duration();
    if (total === 0) {
      return;
    }

    const steps = {
      ArrowRight: 5,
      ArrowLeft: -5,
      ArrowUp: 5,
      ArrowDown: -5,
      PageUp: 60,
      PageDown: -60,
    };

    let target;
    if (event.key in steps) {
      target = video.currentTime + steps[event.key];
    } else if (event.key === 'Home') {
      target = 0;
    } else if (event.key === 'End') {
      target = total;
    } else {
      return;
    }

    event.preventDefault();
    event.stopPropagation();
    onSeek?.(Math.min(total, Math.max(0, target)));
  });

  video.addEventListener('timeupdate', sync);
  video.addEventListener('progress', paintBuffered);
  video.addEventListener('loadedmetadata', sync);
  video.addEventListener('durationchange', sync);

  sync();

  return { element, sync };
}
