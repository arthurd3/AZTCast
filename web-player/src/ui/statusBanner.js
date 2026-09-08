/** The coloured message strip above the player. */
export function createStatusBanner(element) {
  return {
    show(message, kind = 'loading') {
      element.textContent = message;
      element.className = `status ${kind}`;
      element.classList.remove('hidden');
    },
    hide() {
      element.classList.add('hidden');
    },
  };
}
