import { icon } from './icons.js';

const STEPS = [
  { status: 'DOWNLOADING', label: 'Baixando torrent' },
  { status: 'TRANSCODING', label: 'Transcodificando' },
  { status: 'READY', label: 'Pronto' },
];

/**
 * Renders ingestion progress as a track of steps.
 *
 * Built from DOM nodes rather than innerHTML: failureReason comes from the server and can
 * contain ffmpeg or tracker output, i.e. arbitrary text. The same reasoning is written down
 * in videoInfo.js.
 *
 * The percentage is shown for the download and nowhere else. The swarm reports pieces, so that
 * number is measured; ffmpeg reports nothing this pipeline reads, so transcoding shows no bar
 * rather than an invented one — an invented bar that stalls is worse than no bar. Elapsed time
 * carries every stage, computed from the job's own createdAt, which stays true even when the API
 * deduplicates onto an ingestion that started ten minutes ago.
 */
export function createJobProgress(container) {
  let ticker = null;
  let startedAt = null;

  function clear() {
    stopTicking();
    startedAt = null;
    container.replaceChildren();
    container.classList.add('hidden');
  }

  function stopTicking() {
    if (ticker !== null) {
      window.clearInterval(ticker);
      ticker = null;
    }
  }

  function render(job) {
    container.classList.remove('hidden');
    if (job.createdAt) {
      startedAt = Date.parse(job.createdAt);
    }

    const track = document.createElement('ol');
    track.className = 'job-track';

    const reachedIndex = STEPS.findIndex((step) => step.status === job.status);

    STEPS.forEach((step, index) => {
      const item = document.createElement('li');
      const failedHere = job.status === 'FAILED' && index === 0;

      let state = 'pending';
      let glyph = '';
      if (failedHere) {
        state = 'failed';
        glyph = '✕';
      } else if (reachedIndex > index || job.status === 'READY') {
        state = 'done';
        glyph = '✓';
      } else if (reachedIndex === index) {
        state = 'active';
      }

      item.className = `job-step job-step--${state}`;

      const node = document.createElement('span');
      node.className = 'job-step__node';
      node.textContent = glyph;
      node.setAttribute('aria-hidden', 'true');

      const label = document.createElement('span');
      label.className = 'job-step__label';
      // The state is in the text, not only in the colour and the shape of a dot.
      label.textContent = step.label;

      item.append(node, label);

      // Only against the step it actually measures, and only while that step is running: a
      // percentage frozen at 100 next to "Transcodificando" would read as the transcode's own.
      if (step.status === 'DOWNLOADING' && state === 'active' && hasPercent(job)) {
        const percent = document.createElement('span');
        percent.className = 'job-step__percent';
        percent.textContent = `${job.progressPercent}%`;
        item.appendChild(percent);
      }

      track.appendChild(item);
    });

    // One name for the whole track, so a screen reader gets "Transcodificando, etapa 2 de 3"
    // rather than three list items whose meaning is carried by CSS classes.
    track.setAttribute('role', 'group');
    track.setAttribute(
      'aria-label',
      job.status === 'FAILED'
        ? 'A ingestão falhou'
        : `Ingestão: ${STEPS[Math.max(reachedIndex, 0)].label}, etapa ${Math.max(reachedIndex, 0) + 1} de ${STEPS.length}`,
    );

    const children = [track];

    if (job.status === 'DOWNLOADING' && hasPercent(job)) {
      children.push(bar(job.progressPercent));
    }

    if (job.status === 'FAILED' && job.failureReason) {
      const reason = document.createElement('p');
      reason.className = 'job-progress__reason';
      reason.textContent = job.failureReason;
      children.push(reason);
    }

    children.push(footer(job));
    container.replaceChildren(...children);

    // The clock stops the moment the job does; leaving it running would suggest work continues.
    stopTicking();
    if (job.status !== 'READY' && job.status !== 'FAILED') {
      ticker = window.setInterval(updateElapsed, 1000);
    }
    updateElapsed();
  }

  /**
   * The download bar.
   *
   * A real progressbar role rather than a styled div: the width is the only thing a sighted
   * viewer reads, and without aria-valuenow there is nothing left for anyone else.
   */
  function bar(percent) {
    const track = document.createElement('div');
    track.className = 'job-progress__bar';
    track.setAttribute('role', 'progressbar');
    track.setAttribute('aria-valuenow', String(percent));
    track.setAttribute('aria-valuemin', '0');
    track.setAttribute('aria-valuemax', '100');
    track.setAttribute('aria-label', 'Progresso do download');

    const fill = document.createElement('div');
    fill.className = 'job-progress__bar-fill';
    fill.style.width = `${percent}%`;

    track.appendChild(fill);
    return track;
  }

  function footer(job) {
    const meta = document.createElement('div');
    meta.className = 'job-progress__meta';

    const elapsed = document.createElement('span');
    elapsed.className = 'job-progress__elapsed';
    elapsed.dataset.role = 'elapsed';
    meta.appendChild(elapsed);

    if (job.videoId) {
      meta.appendChild(idBlock(job.videoId));
    }
    return meta;
  }

  function updateElapsed() {
    const target = container.querySelector('[data-role="elapsed"]');
    if (!target) {
      return;
    }
    target.textContent = startedAt ? `Decorrido ${duration(Date.now() - startedAt)}` : '';
  }

  clear();
  return { render, clear };
}

/** Whether the API sent a usable percentage. Older builds of it did not send one at all. */
function hasPercent(job) {
  return Number.isFinite(job.progressPercent);
}

/** The video id with a copy button, because the next thing anyone does with it is copy it. */
function idBlock(videoId) {
  const wrapper = document.createElement('span');
  wrapper.className = 'job-progress__id';

  const text = document.createElement('span');
  text.textContent = videoId;

  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'btn btn--icon btn--sm';
  button.setAttribute('aria-label', 'Copiar o ID do vídeo');
  button.appendChild(icon('copy'));

  button.addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(videoId);
    } catch {
      // Denied, or no clipboard permission in this context. The id is on screen either way.
      return;
    }
    button.replaceChildren(icon('check'));
    button.setAttribute('aria-label', 'ID copiado');
    window.setTimeout(() => {
      button.replaceChildren(icon('copy'));
      button.setAttribute('aria-label', 'Copiar o ID do vídeo');
    }, 1600);
  });

  wrapper.append(text, button);
  return wrapper;
}

/** Elapsed time as `12s`, `4m 09s` or `1h 06m`. */
function duration(milliseconds) {
  const total = Math.max(0, Math.floor(milliseconds / 1000));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;

  if (hours > 0) {
    return `${hours}h ${String(minutes).padStart(2, '0')}m`;
  }
  if (minutes > 0) {
    return `${minutes}m ${String(seconds).padStart(2, '0')}s`;
  }
  return `${seconds}s`;
}
