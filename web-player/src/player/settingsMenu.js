import { icon } from '../ui/icons.js';

const SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 2];

/**
 * The quality, subtitle and speed popover.
 *
 * Quality is only offered when there is a choice to make. On Safari's native HLS path there is
 * no hls.js and therefore no level list, so that group is left out entirely rather than shown
 * empty — the alternative is a menu that looks broken on exactly one browser.
 *
 * Subtitles appear only when the stream carries some. ADR-0012 decided against a captions
 * button on the grounds that nothing produced captions; the pipeline republishes the source's
 * text tracks as WebVTT now, so the reason has gone and the control has not.
 *
 * Built as a menu with radio semantics, because that is what it is: groups with one checked
 * item each.
 */
export function createSettingsMenu(video, { onSelectLevel, onSelectSubtitle, onOpenChange } = {}) {
  const element = document.createElement('div');
  element.className = 'menu';

  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'btn btn--icon control-btn';
  button.setAttribute('aria-label', 'Configurações de reprodução');
  button.setAttribute('aria-haspopup', 'true');
  button.setAttribute('aria-expanded', 'false');
  button.appendChild(icon('settings'));

  const panel = document.createElement('div');
  panel.className = 'menu__panel hidden';
  panel.setAttribute('role', 'menu');
  panel.setAttribute('aria-label', 'Configurações de reprodução');

  element.append(button, panel);

  /** hls.js levels, highest index last. Empty on the native-HLS path. */
  let levels = [];
  /** -1 means automatic. */
  let currentLevel = -1;
  /** Subtitle renditions, in master-playlist order. Empty when the stream carries none. */
  let subtitles = [];
  /** -1 means off, which is where it starts and where it stays until someone asks. */
  let currentSubtitle = -1;

  function open() {
    panel.classList.remove('hidden');
    button.setAttribute('aria-expanded', 'true');
    onOpenChange?.(true);
    // Focus lands inside so arrows and Escape work without a second Tab.
    panel.querySelector('[role="menuitemradio"]')?.focus();
    document.addEventListener('pointerdown', onOutside, true);
  }

  function close({ restoreFocus = true } = {}) {
    if (panel.classList.contains('hidden')) {
      return;
    }
    panel.classList.add('hidden');
    button.setAttribute('aria-expanded', 'false');
    onOpenChange?.(false);
    document.removeEventListener('pointerdown', onOutside, true);
    if (restoreFocus) {
      button.focus();
    }
  }

  function onOutside(event) {
    if (!element.contains(event.target)) {
      // No focus restore: the viewer clicked somewhere else and that is where they want to be.
      close({ restoreFocus: false });
    }
  }

  function item({ label, checked, onSelect }) {
    const option = document.createElement('button');
    option.type = 'button';
    option.className = 'menu__item';
    option.setAttribute('role', 'menuitemradio');
    option.setAttribute('aria-checked', String(checked));
    // Roving tabindex: one stop for the whole group, arrows move within it.
    option.tabIndex = checked ? 0 : -1;

    const text = document.createElement('span');
    text.textContent = label;
    option.append(icon('check', 'menu__check'), text);

    option.addEventListener('click', () => {
      onSelect();
      render();
      close();
    });
    return option;
  }

  function group(title, items) {
    const section = document.createElement('div');
    section.className = 'menu__group';

    const heading = document.createElement('p');
    heading.className = 'menu__title';
    heading.textContent = title;

    section.append(heading, ...items);
    return section;
  }

  function render() {
    const sections = [];

    if (levels.length > 0) {
      const options = [
        item({
          label: autoLabel(),
          checked: currentLevel === -1,
          onSelect: () => onSelectLevel?.(-1),
        }),
        // Highest first: it is the one people go looking for.
        ...[...levels]
          .map((level, index) => ({ level, index }))
          .reverse()
          .map(({ level, index }) =>
            item({
              label: `${level.height}p`,
              checked: currentLevel === index,
              onSelect: () => onSelectLevel?.(index),
            }),
          ),
      ];
      sections.push(group('Qualidade', options));
    }

    if (subtitles.length > 0) {
      const options = [
        item({
          label: 'Desativadas',
          checked: currentSubtitle === -1,
          onSelect: () => onSelectSubtitle?.(-1),
        }),
        ...subtitles.map((track) =>
          item({
            label: track.label,
            checked: currentSubtitle === track.id,
            onSelect: () => onSelectSubtitle?.(track.id),
          }),
        ),
      ];
      sections.push(group('Legendas', options));
    }

    sections.push(
      group(
        'Velocidade',
        SPEEDS.map((rate) =>
          item({
            label: rate === 1 ? 'Normal' : `${rate}×`,
            checked: Math.abs(video.playbackRate - rate) < 0.01,
            onSelect: () => {
              video.playbackRate = rate;
            },
          }),
        ),
      ),
    );

    panel.replaceChildren(...sections);
  }

  /** "Automático" alone reads as a state; naming the level it settled on makes it informative. */
  function autoLabel() {
    const active = levels[currentLevelHint()];
    return active ? `Automático (${active.height}p)` : 'Automático';
  }

  function currentLevelHint() {
    return Number.parseInt(video.dataset.activeLevel ?? '-1', 10);
  }

  button.addEventListener('click', () => {
    if (panel.classList.contains('hidden')) {
      render();
      open();
    } else {
      close();
    }
  });

  panel.addEventListener('keydown', (event) => {
    const items = [...panel.querySelectorAll('[role="menuitemradio"]')];
    const index = items.indexOf(document.activeElement);

    if (event.key === 'Escape') {
      event.stopPropagation();
      close();
      return;
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      const next = event.key === 'ArrowDown' ? index + 1 : index - 1;
      // Wraps, which is what a menu does and what saves a long press at either end.
      items[(next + items.length) % items.length]?.focus();
    }
  });

  video.addEventListener('ratechange', () => {
    if (!panel.classList.contains('hidden')) {
      render();
    }
  });

  render();

  return {
    element,

    /** Called whenever hls.js reports a new ladder or switches level on its own. */
    setQuality(nextLevels, active, loading) {
      levels = nextLevels ?? [];
      currentLevel = active ?? -1;
      video.dataset.activeLevel = String(loading ?? active ?? -1);
      if (!panel.classList.contains('hidden')) {
        render();
      }
    },

    /** Called whenever the stream's subtitle renditions or the selected one change. */
    setSubtitles(tracks, active) {
      subtitles = tracks ?? [];
      currentSubtitle = active ?? -1;
      if (!panel.classList.contains('hidden')) {
        render();
      }
    },

    close,
    isOpen: () => !panel.classList.contains('hidden'),
  };
}
