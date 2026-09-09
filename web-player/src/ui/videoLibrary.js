import { listVideos, posterUrl } from '../api/streamClient.js';

const WHEN = new Intl.DateTimeFormat('pt-BR', {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});

const SIZE = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 1 });

/**
 * The list of watchable videos, which replaced pasting a UUID into a text field.
 *
 * Built from DOM nodes rather than innerHTML, and here the habit earns its keep more than anywhere
 * else in this app: a title is the filename from inside a torrent, so it is text a stranger chose.
 * The same reasoning is written down in jobProgress.js and videoInfo.js.
 */
export function createVideoLibrary(container, { onSelect }) {
  /** Kept outside render() so a refresh does not lose the highlight on what is playing. */
  let selectedId = null;

  function note(text, kind) {
    const paragraph = document.createElement('p');
    paragraph.className = kind ? `library-note ${kind}` : 'library-note';
    paragraph.textContent = text;
    container.replaceChildren(paragraph);
  }

  function render(videos) {
    if (videos.length === 0) {
      note('Nenhum vídeo processado ainda. Envie um magnet acima.');
      return;
    }
    container.replaceChildren(...videos.map(card));
    highlight();
  }

  function card(video) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'video-card';
    button.dataset.videoId = video.videoId;

    const title = document.createElement('span');
    title.className = 'video-card-title';
    // No title means the video was transcoded before the API recorded one. The id is all there is.
    title.textContent = video.title ?? shortId(video.videoId);

    const meta = document.createElement('span');
    meta.className = 'video-card-meta';
    meta.textContent = describe(video);

    button.append(thumbnail(video), title, meta);
    button.addEventListener('click', () => onSelect(video.videoId));
    return button;
  }

  function highlight() {
    container.querySelectorAll('.video-card').forEach((element) => {
      element.classList.toggle('selected', element.dataset.videoId === selectedId);
    });
  }

  return {
    async load() {
      try {
        render(await listVideos());
      } catch (error) {
        // Rendered here rather than in the status banner, which belongs to playback: a library that
        // failed to load should not look like a video that failed to play.
        note(`Não foi possível carregar a lista: ${error.message}`, 'error');
      }
    },
    select(videoId) {
      selectedId = videoId;
      highlight();
    },
    clear() {
      container.replaceChildren();
    },
  };
}

/**
 * The poster frame, or a placeholder holding the same space.
 *
 * The placeholder is not cosmetic: without it a library of mixed videos would jump around as
 * thumbnails loaded, and anything encoded before posters existed has none to show.
 */
function thumbnail(video) {
  const frame = document.createElement('div');
  frame.className = 'video-card-thumb';

  const source = posterUrl(video);
  if (!source) {
    frame.classList.add('empty');
    return frame;
  }

  const image = document.createElement('img');
  image.src = source;
  image.alt = ''; // Decorative: the title is the next node down, so announcing it twice is noise.
  image.loading = 'lazy';
  // A poster reaped between the listing and the paint should look like no poster, not like a
  // broken image.
  image.addEventListener('error', () => {
    image.remove();
    frame.classList.add('empty');
  });
  frame.appendChild(image);
  return frame;
}

function describe(video) {
  const parts = [WHEN.format(new Date(video.readyAt))];
  if (video.qualities.length > 0) {
    parts.push(video.qualities.join(' · '));
  }
  parts.push(size(video.sizeBytes));
  return parts.join(' · ');
}

function size(bytes) {
  const megabytes = bytes / 1024 / 1024;
  return megabytes >= 1024 ? `${SIZE.format(megabytes / 1024)} GB` : `${SIZE.format(megabytes)} MB`;
}

function shortId(videoId) {
  return `${videoId.slice(0, 8)}…`;
}
