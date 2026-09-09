const WHEN = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'long', timeStyle: 'short' });
const SIZE = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 1 });

/**
 * The panel under the player: what this video is and what the stream is currently doing.
 *
 * Replaces videoInfo.js, which rendered the same two facts as a sentence above the old
 * dropdown. Built as nodes rather than innerHTML for the reason recorded there: the level
 * labels come from a manifest the server generated, and string-concatenating that into HTML
 * is a habit worth not having.
 */
export function createVideoMeta(element) {
  /** The catalogue entry, once it arrives. Null until then, and for a video not listed. */
  let video = null;

  function field(label, value) {
    const wrapper = document.createElement('div');

    const name = document.createElement('span');
    name.className = 'watch__meta-label';
    name.textContent = label;

    const text = document.createElement('span');
    text.textContent = value;

    wrapper.append(name, text);
    return wrapper;
  }

  function render(player) {
    const fields = [];

    const levels = player?.levels() ?? [];
    if (levels.length > 0) {
      const current = player.currentLevel();
      const active = levels[current === -1 ? player.loadLevel() : current];
      const mode =
        current === -1
          ? `Automático${active ? ` · ${active.height}p` : ''}`
          : `Manual · ${active ? `${active.height}p` : '—'}`;
      fields.push(field('Qualidade', mode));
      fields.push(field('Disponíveis', levels.map((level) => `${level.height}p`).join(' · ')));
    } else if (video?.qualities?.length) {
      // The native-HLS path exposes no ladder, but the catalogue knows what was encoded.
      fields.push(field('Disponíveis', video.qualities.join(' · ')));
    }

    if (video) {
      fields.push(field('Tamanho', size(video.sizeBytes)));
      fields.push(field('Pronto em', WHEN.format(new Date(video.readyAt))));
    }

    element.classList.toggle('hidden', fields.length === 0);
    element.replaceChildren(...fields);
  }

  return {
    /** Called once the catalogue answers. Safe to never call. */
    describe(entry) {
      video = entry;
    },
    render,
    clear() {
      element.replaceChildren();
      element.classList.add('hidden');
    },
  };
}

function size(bytes) {
  const megabytes = bytes / 1024 / 1024;
  return megabytes >= 1024 ? `${SIZE.format(megabytes / 1024)} GB` : `${SIZE.format(megabytes)} MB`;
}
