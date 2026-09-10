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

  container.replaceChildren(search, sort, kept, count);

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
  };
}
