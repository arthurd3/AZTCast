import { icon } from './icons.js';

/** Long enough to swallow a burst of typing, short enough not to feel laggy. */
const DEBOUNCE_MS = 150;

const SORTS = [
  { value: 'recent', label: 'Mais recentes' },
  { value: 'title', label: 'Título (A–Z)' },
  { value: 'size', label: 'Maiores primeiro' },
  { value: 'quality', label: 'Melhor qualidade' },
];

/**
 * Search and sort above the library grid.
 *
 * Both act on the array already in memory — see the note in videoLibrary.js about why there is
 * no request per keystroke. The toolbar therefore owns no data, only the two controls, the result
 * count and the disk reading beside it.
 */
export function createLibraryToolbar(container, { onChange }) {
  const search = document.createElement('div');
  search.className = 'library-toolbar__search';
  search.appendChild(icon('search', ''));

  const input = document.createElement('input');
  input.type = 'search';
  input.className = 'input';
  input.id = 'librarySearch';
  input.placeholder = 'Buscar por título…';
  input.autocomplete = 'off';
  input.setAttribute('aria-label', 'Buscar vídeos por título');
  search.appendChild(input);

  const sort = document.createElement('select');
  sort.className = 'select';
  sort.setAttribute('aria-label', 'Ordenar biblioteca');
  sort.replaceChildren(...SORTS.map((option) => new Option(option.label, option.value)));

  // polite, not assertive: the count changing is confirmation, not an interruption — and it
  // changes on every keystroke.
  const count = document.createElement('p');
  count.className = 'library-toolbar__count';
  count.setAttribute('role', 'status');
  count.setAttribute('aria-live', 'polite');

  /*
   * A filter, not a sort, so it is its own control rather than a fifth entry in the select.
   * Folding it into the sort list would let someone pick "Salvos" and lose their ordering, and
   * there would be no way to see saved videos newest-first.
   */
  const kept = document.createElement('button');
  kept.type = 'button';
  kept.className = 'btn btn--ghost library-toolbar__kept';
  kept.setAttribute('aria-pressed', 'false');
  kept.append(icon('bookmark', 'btn__icon'), document.createTextNode('Salvos'));

  /*
   * How much room is left, next to how many videos there are. It earns the space because nothing
   * deletes itself: the disk is the only limit the library has now, and a number that only appears
   * once an ingestion is refused would arrive after the decision it was meant to inform.
   *
   * Quiet, and absent entirely until it is known — an empty element is better than a confident 0 GB
   * on a host whose filesystem could not be read.
   */
  const space = document.createElement('p');
  space.className = 'library-toolbar__space';

  container.replaceChildren(search, sort, kept, count, space);

  let timer = null;
  let keptOnly = false;
  function emit() {
    onChange({ query: input.value, sort: sort.value, keptOnly });
  }

  kept.addEventListener('click', () => {
    keptOnly = !keptOnly;
    kept.setAttribute('aria-pressed', keptOnly ? 'true' : 'false');
    kept.classList.toggle('btn--active', keptOnly);
    kept.replaceChildren(
      icon(keptOnly ? 'bookmarkOn' : 'bookmark', 'btn__icon'),
      document.createTextNode('Salvos'),
    );
    emit();
  });

  input.addEventListener('input', () => {
    window.clearTimeout(timer);
    timer = window.setTimeout(emit, DEBOUNCE_MS);
  });

  // The change event does not fire until blur on a select in some browsers when driven from
  // the keyboard; it does fire on arrow keys in all of them, which is the case that matters.
  sort.addEventListener('change', emit);

  return {
    /** Reveals the toolbar. Hidden until there is something to sort. */
    show() {
      container.classList.remove('hidden');
    },

    hide() {
      container.classList.add('hidden');
    },

    setCount(shown, total) {
      count.textContent =
        shown === total
          ? `${total} ${total === 1 ? 'vídeo' : 'vídeos'}`
          : `${shown} de ${total} vídeos`;
    },

    /**
     * Shows what the library occupies and what is left on the volume.
     *
     * Says nothing at all when the volume could not be read, rather than guessing. Warns when the
     * free space is within a whisker of the floor, because that is the point at which the next
     * magnet is going to be refused and the person pasting it deserves to know first.
     */
    setSpace(storage) {
      if (!storage || storage.usableBytes === null || storage.usableBytes === undefined) {
        space.textContent = '';
        space.classList.remove('library-toolbar__space--low');
        return;
      }
      const low = storage.minFreeBytes > 0 && storage.usableBytes < storage.minFreeBytes * 2;
      space.classList.toggle('library-toolbar__space--low', low);
      space.textContent = `${bytes(storage.mediaBytes)} em vídeos · ${bytes(storage.usableBytes)} livres`;
    },
  };
}

/** Bytes as the nearest sensible unit. Binary steps, decimal comma, as the cards already use. */
function bytes(value) {
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let size = value;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit += 1;
  }
  return `${new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 1 }).format(size)} ${units[unit]}`;
}
