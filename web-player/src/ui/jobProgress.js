const STEPS = [
  { status: 'DOWNLOADING', label: 'Baixando torrent' },
  { status: 'TRANSCODING', label: 'Transcodificando' },
  { status: 'READY', label: 'Pronto' },
];

/**
 * Renders ingestion progress as an ordered list of steps.
 *
 * Built from DOM nodes rather than innerHTML: failureReason comes from the server and can
 * contain ffmpeg or tracker output, i.e. arbitrary text. The same reasoning is written down
 * in videoInfo.js.
 */
export function createJobProgress(container) {
  function clear() {
    container.replaceChildren();
    container.classList.add('hidden');
  }

  function render(job) {
    container.classList.remove('hidden');
    const list = document.createElement('ol');
    list.className = 'steps';

    const reachedIndex = STEPS.findIndex((step) => step.status === job.status);

    STEPS.forEach((step, index) => {
      const item = document.createElement('li');
      const failedHere = job.status === 'FAILED' && index === 0;
      if (failedHere) {
        item.className = 'step failed';
        item.textContent = `✕ ${step.label}`;
      } else if (reachedIndex > index || job.status === 'READY') {
        item.className = 'step done';
        item.textContent = `✓ ${step.label}`;
      } else if (reachedIndex === index) {
        item.className = 'step active';
        item.textContent = `● ${step.label}…`;
      } else {
        item.className = 'step pending';
        item.textContent = `○ ${step.label}`;
      }
      list.appendChild(item);
    });

    container.replaceChildren(list);

    if (job.status === 'FAILED' && job.failureReason) {
      const reason = document.createElement('p');
      reason.className = 'step-reason';
      reason.textContent = job.failureReason;
      container.appendChild(reason);
    }

    if (job.videoId) {
      const id = document.createElement('p');
      id.className = 'step-id';
      id.textContent = `ID: ${job.videoId}`;
      container.appendChild(id);
    }
  }

  clear();
  return { render, clear };
}
