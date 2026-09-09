/** The message strip: one per page, driven by whatever that page is doing. */
export function createStatusBanner(element) {
  return {
    /** @param {'loading'|'success'|'error'} kind */
    show(message, kind = 'loading') {
      element.textContent = message;
      element.className = `banner banner--${kind}`;
      // An error is worth interrupting a screen reader for; progress is not. The element is
      // aria-live in the markup, so this only picks how insistent it should be.
      element.setAttribute('aria-live', kind === 'error' ? 'assertive' : 'polite');
    },
    hide() {
      element.classList.add('hidden');
    },
  };
}
