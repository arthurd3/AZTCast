/** The Auto / 720p / 240p dropdown, rebuilt whenever a manifest is parsed. */
export function createQualitySelector(selectElement, onSelect) {
  selectElement.addEventListener('change', () => {
    onSelect(Number.parseInt(selectElement.value, 10));
  });

  return {
    populate(levels) {
      const auto = new Option('Auto', '-1');
      const options = levels.map((level, index) => new Option(`${level.height}p`, String(index)));
      selectElement.replaceChildren(auto, ...options);
      selectElement.value = '-1';
      selectElement.classList.remove('hidden');
    },
    /** Keeps the dropdown in step when hls.js switches level on its own. */
    syncTo(level) {
      selectElement.value = String(level);
    },
    hide() {
      selectElement.classList.add('hidden');
    },
  };
}
