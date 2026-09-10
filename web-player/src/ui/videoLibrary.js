import { deleteVideo, listVideos, posterUrl, setVideoKept } from '../api/streamClient.js';
import { fraction } from '../player/progressStore.js';
import { icon } from './icons.js';

const WHEN = new Intl.DateTimeFormat('pt-BR', {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});

const SIZE = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 1 });

/** How many placeholder cards to show while the listing is in flight. */
const SKELETON_COUNT = 8;

/**
 * The list of watchable videos, which replaced pasting a UUID into a text field.
 *
 * Built from DOM nodes rather than innerHTML, and here the habit earns its keep more than
 * anywhere else in this app: a title is the filename from inside a torrent, so it is text a
 * stranger chose. The same reasoning is written down in jobProgress.js and videoInfo.js.
 *
 * Filtering and sorting happen here, over the array the listing already returned. There is no
 * query parameter to send: `GET /api/v1/videos` returns everything on disk, and a round trip per
 * keystroke would buy nothing over filtering an array that is already in the page. Nothing expires
 * out of that listing any more, so it grows with the library — if it ever gets big enough for this
 * to feel slow, the fix is a query parameter, not a shorter library.
 */
export function createVideoLibrary(
  container,
  { onSelect, onLoad, onError, exclude, emptyAction } = {},
) {
  /** Kept outside render() so a refresh does not lose the highlight on what is playing. */
  let selectedId = null;
  /** Everything the API returned, before the toolbar narrows it. */
  let all = [];
  let view = { query: '', sort: 'recent', keptOnly: false };

  function note(title, body, kind, action) {
    const wrapper = document.createElement('div');
    wrapper.className = kind ? `library-note library-note--${kind}` : 'library-note';

    if (kind !== 'error') {
      wrapper.appendChild(constellation());
    }

    const heading = document.createElement('p');
    heading.className = 'library-note__title';
    heading.textContent = title;

    const text = document.createElement('p');
    text.className = 'library-note__body';
    text.textContent = body;

    wrapper.append(heading, text);

    // An empty library is the first thing a new install shows, so it gets a way out of itself
    // rather than only an explanation. The rail on the watch page passes none and hides instead.
    if (action) {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'btn btn--primary';
      button.textContent = action.label;
      button.addEventListener('click', action.onClick);
      wrapper.appendChild(button);
    }

    container.replaceChildren(wrapper);
  }

  function skeleton() {
    const cards = Array.from({ length: SKELETON_COUNT }, () => {
      const card = document.createElement('div');
      card.className = 'skeleton-card';

      const thumb = document.createElement('div');
      thumb.className = 'skeleton skeleton-card__thumb';

      const line = document.createElement('div');
      line.className = 'skeleton skeleton-card__line';

      const short = document.createElement('div');
      short.className = 'skeleton skeleton-card__line skeleton-card__line--short';

      card.append(thumb, line, short);
      return card;
    });
    container.replaceChildren(...cards);
    // The grid is a live region for the count, but eight shimmering boxes are not news.
    container.setAttribute('aria-busy', 'true');
  }

  function render() {
    container.removeAttribute('aria-busy');
    const videos = arrange(all, view);

    if (all.length === 0) {
      note(
        'Nenhum vídeo ainda',
        'Cole um link magnet no campo acima. Quando a transcodificação terminar, o vídeo aparece aqui.',
        null,
        emptyAction,
      );
      return videos.length;
    }
    if (videos.length === 0) {
      if (view.keptOnly && !view.query.trim()) {
        note(
          'Nenhum vídeo salvo',
          'Salvar um vídeo pede confirmação antes de excluí-lo. Use o marcador no canto de um card.',
        );
      } else {
        note(
          'Nada corresponde à busca',
          `Nenhum título contém “${view.query}”. Tente outro termo.`,
        );
      }
      return 0;
    }

    container.replaceChildren(...videos.map(card));
    highlight();
    return videos.length;
  }

  function card(video) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'video-card';
    button.dataset.videoId = video.videoId;

    const title = video.title ?? shortId(video.videoId);
    // The card is one control, so it gets one name. Without this the accessible name would be
    // the poster, the badges and the metadata read end to end.
    button.setAttribute('aria-label', `Assistir ${title}`);

    const body = document.createElement('span');
    body.className = 'video-card__body';

    const name = document.createElement('span');
    name.className = 'video-card__title';
    name.textContent = title;

    const meta = document.createElement('span');
    meta.className = 'video-card__meta';
    meta.textContent = describe(video);

    body.append(name, meta);
    button.append(thumbnail(video), body);
    button.addEventListener('click', () => onSelect?.(video.videoId));

    // A shell around the card, because the card is itself a <button> and a button inside a button
    // is invalid HTML — browsers recover from it by dropping one of them, and which one is not
    // something to rely on. The shell is the grid item; the card and the keep toggle are siblings.
    const shell = document.createElement('div');
    shell.className = 'video-card-shell';
    shell.append(button, keepToggle(video), deleteToggle(video));
    return shell;
  }

  /**
   * The save toggle, which decides whether deleting this video stops to ask first.
   *
   * Optimistic: the marker is a file write on the server and the failure mode is a stale icon, not
   * lost media, so the button flips immediately and rolls back if the request fails. Waiting for a
   * round trip to acknowledge a bookmark is the sort of latency people read as a broken button.
   */
  function keepToggle(video) {
    const toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.className = 'video-card__keep';

    function paint(kept) {
      toggle.classList.toggle('video-card__keep--on', kept);
      toggle.setAttribute('aria-pressed', kept ? 'true' : 'false');
      const label = kept ? 'Remover dos salvos' : 'Salvar vídeo';
      toggle.setAttribute('aria-label', label);
      toggle.title = kept ? 'Salvo — a exclusão pede confirmação' : 'Salvar';
      toggle.replaceChildren(icon(kept ? 'bookmarkOn' : 'bookmark', 'video-card__keep-icon'));
    }

    paint(video.kept);

    toggle.addEventListener('click', async () => {
      const next = !video.kept;
      video.kept = next;
      paint(next);
      toggle.disabled = true;
      try {
        await setVideoKept(video.videoId, next);
      } catch (error) {
        video.kept = !next;
        paint(!next);
        // Not note(): that replaces the whole grid, so one failed bookmark would blank the
        // library the viewer is looking at. The rollback above is already the visible feedback;
        // this only adds words, and the host decides where they go.
        onError?.(error.message);
        toggle.title = error.message;
      } finally {
        toggle.disabled = false;
      }
    });
    return toggle;
  }

  /**
   * The delete control: two clicks, never one.
   *
   * Deliberately not `confirm()`. A modal blocks the whole page to ask about one card, and the
   * question it asks is somebody else's wording in somebody else's box — the same reason nothing
   * else in this file reaches for innerHTML. Arming the button in place puts the question where
   * the answer is, and clicking anything else disarms it.
   *
   * Not optimistic, unlike the save toggle beside it. That one risks a stale icon; this one risks
   * a card for a video that is gone, or worse, a card removed for a video that is still there. It
   * waits for the 204.
   *
   * A saved video costs a third click. The 409 comes back from the API rather than being predicted
   * here, so the marker is enforced where it lives even if this page is out of date about it.
   */
  function deleteToggle(video) {
    const toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.className = 'video-card__delete';
    let armed = false;

    function paint() {
      toggle.classList.toggle('video-card__delete--armed', armed);
      const name = label(video);
      const text = armed ? `Confirmar exclusão de ${name}` : `Excluir ${name}`;
      toggle.setAttribute('aria-label', text);
      toggle.title = armed ? 'Clique de novo para excluir' : 'Excluir';
      toggle.replaceChildren(icon(armed ? 'check' : 'trash', 'video-card__delete-icon'));
    }

    function disarm() {
      if (!armed) return;
      armed = false;
      paint();
    }

    paint();
    // Anywhere else in the document disarms it, so an armed button cannot be left lying around for
    // a later, unrelated click to land on.
    document.addEventListener('click', disarm);

    toggle.addEventListener('click', async (event) => {
      event.stopPropagation();
      if (!armed) {
        armed = true;
        paint();
        return;
      }
      toggle.disabled = true;
      try {
        await deleteVideo(video.videoId, { force: video.kept });
        document.removeEventListener('click', disarm);
        all = all.filter((other) => other.videoId !== video.videoId);
        const shown = render();
        onLoad?.({ total: all.length, shown, failed: false });
      } catch (error) {
        toggle.disabled = false;
        disarm();
        // Not note(): that replaces the whole grid, so one failed delete would blank the library
        // the viewer is looking at. Same reasoning as the save toggle above.
        onError?.(error.message);
        toggle.title = error.message;
      }
    });
    return toggle;
  }

  function highlight() {
    container.querySelectorAll('.video-card').forEach((element) => {
      const current = element.dataset.videoId === selectedId;
      element.classList.toggle('video-card--selected', current);
      element.setAttribute('aria-current', current ? 'true' : 'false');
    });
  }

  return {
    async load() {
      skeleton();
      try {
        const listed = await listVideos();
        // The watch page's rail passes the video already playing, so it is not offered as
        // somewhere to go next.
        all = exclude ? listed.filter((video) => video.videoId !== exclude) : listed;
      } catch (error) {
        container.removeAttribute('aria-busy');
        // Rendered here rather than in the status banner, which belongs to ingestion: a library
        // that failed to load should not look like a magnet that failed to submit.
        note('Não foi possível carregar a biblioteca', error.message, 'error');
        onLoad?.({ total: 0, shown: 0, failed: true });
        return;
      }
      const shown = render();
      onLoad?.({ total: all.length, shown, failed: false });
    },

    /** Re-renders from the cached listing. Returns how many cards are now on screen. */
    filter(next) {
      view = { ...view, ...next };
      return render();
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

/** Narrows and orders the listing for the current toolbar state. */
function arrange(videos, { query, sort, keptOnly }) {
  const needle = fold(query.trim());
  let matched = needle
    ? videos.filter((video) => fold(video.title ?? video.videoId).includes(needle))
    : [...videos];
  if (keptOnly) {
    matched = matched.filter((video) => video.kept);
  }

  const order = {
    recent: (left, right) => Date.parse(right.readyAt) - Date.parse(left.readyAt),
    title: (left, right) => label(left).localeCompare(label(right), 'pt-BR'),
    size: (left, right) => right.sizeBytes - left.sizeBytes,
    quality: (left, right) => height(right) - height(left),
  };
  return matched.sort(order[sort] ?? order.recent);
}

/**
 * Accent- and case-insensitive, because the titles are Portuguese filenames and nobody types
 * "Ilhá" to find "Ilha". NFD splits a letter from its accent; the range then drops the accent.
 */
function fold(text) {
  return text
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase();
}

function label(video) {
  return video.title ?? video.videoId;
}

/** The tallest rendition, as a number, for sorting. `qualities` is highest-first strings. */
function height(video) {
  return Number.parseInt(video.qualities?.[0] ?? '0', 10) || 0;
}

/**
 * The poster frame, or a placeholder holding the same space.
 *
 * The placeholder is not cosmetic: without it a library of mixed videos would jump around as
 * thumbnails loaded, and anything encoded before posters existed has none to show.
 */
function thumbnail(video) {
  const frame = document.createElement('span');
  frame.className = 'video-card__thumb';

  const source = posterUrl(video);
  if (source) {
    const image = document.createElement('img');
    image.src = source;
    image.alt = ''; // Decorative: the button's aria-label already names the video.
    image.loading = 'lazy';
    image.decoding = 'async';
    // A poster reaped between the listing and the paint should look like no poster, not like a
    // broken image.
    image.addEventListener('error', () => {
      image.remove();
      frame.classList.add('video-card__thumb--empty');
    });
    frame.appendChild(image);
  } else {
    frame.classList.add('video-card__thumb--empty');
  }

  const play = document.createElement('span');
  play.className = 'video-card__play';
  const disc = document.createElement('span');
  disc.appendChild(icon('play', ''));
  play.appendChild(disc);
  frame.appendChild(play);

  if (video.qualities?.length > 0) {
    const badges = document.createElement('span');
    badges.className = 'video-card__badges';
    const top = document.createElement('span');
    top.className = 'badge badge--accent';
    top.textContent = video.qualities[0];
    badges.appendChild(top);
    frame.appendChild(badges);
  }

  const progress = fraction(video.videoId);
  if (progress !== null) {
    const bar = document.createElement('span');
    bar.className = 'video-card__resume';
    const fill = document.createElement('span');
    fill.style.width = `${Math.round(progress * 100)}%`;
    bar.appendChild(fill);
    frame.appendChild(bar);
  }

  return frame;
}

function describe(video) {
  const parts = [WHEN.format(new Date(video.readyAt))];
  if (video.qualities.length > 1) {
    parts.push(`${video.qualities.length} qualidades`);
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

/** The empty-state art: a small constellation, drawn rather than shipped as an asset. */
function constellation() {
  const ns = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(ns, 'svg');
  svg.setAttribute('viewBox', '0 0 140 90');
  svg.setAttribute('class', 'library-note__art');
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('focusable', 'false');

  const points = [
    [18, 62],
    [42, 34],
    [68, 48],
    [92, 20],
    [118, 44],
    [104, 72],
  ];

  const line = document.createElementNS(ns, 'polyline');
  line.setAttribute('points', points.map(([x, y]) => `${x},${y}`).join(' '));
  line.setAttribute('fill', 'none');
  line.setAttribute('stroke', 'currentColor');
  line.setAttribute('stroke-opacity', '0.28');
  line.setAttribute('stroke-width', '1.2');
  svg.appendChild(line);

  points.forEach(([x, y], index) => {
    const star = document.createElementNS(ns, 'circle');
    star.setAttribute('cx', String(x));
    star.setAttribute('cy', String(y));
    star.setAttribute('r', index % 2 === 0 ? '3.2' : '2.2');
    star.setAttribute('fill', 'currentColor');
    star.setAttribute('opacity', index % 2 === 0 ? '0.85' : '0.5');
    svg.appendChild(star);
  });

  return svg;
}
