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
 * no request per keystroke. The toolbar therefore owns no data, only the two controls and the
 * result count.
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

  container.replaceChildren(search, sort, count);

  let timer = null;
  function emit() {
    onChange({ query: input.value, sort: sort.value });
  }

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
  };
}
