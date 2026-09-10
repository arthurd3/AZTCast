/** The message strip: one per page, driven by whatever that page is doing. */
export function createStatusBanner(element) {
  return {
    /**
     * @param {string} message
     * @param {'loading'|'success'|'error'} kind
     * @param {{label: string, onSelect: () => void}} [action] an offer to do something about it
     */
    show(message, kind = 'loading', action = null) {
      element.className = `banner banner--${kind}`;
      // An error is worth interrupting a screen reader for; progress is not. The element is
      // aria-live in the markup, so this only picks how insistent it should be.
      element.setAttribute('aria-live', kind === 'error' ? 'assertive' : 'polite');

      // Rebuilt from nodes rather than assigned as text, because a message can now sit beside a
      // button — and because these strings carry server output, which is arbitrary text.
      const text = document.createElement('span');
      text.textContent = message;
      if (!action) {
        element.replaceChildren(text);
        return;
      }

      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'btn btn--sm';
      button.textContent = action.label;
      button.addEventListener('click', () => {
        button.disabled = true;
        action.onSelect();
      });
      element.replaceChildren(text, button);
    },
    hide() {
      element.classList.add('hidden');
    },
  };
}
