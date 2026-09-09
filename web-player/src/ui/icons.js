/**
 * The icon set, as path data.
 *
 * Built with createElementNS rather than innerHTML. The strings here are ours and would be
 * safe either way, but the rule in this codebase is that no module assembles markup from
 * strings — see videoLibrary.js and jobProgress.js for the cases where it genuinely matters.
 * Keeping the habit here means there is no innerHTML call anywhere to audit.
 *
 * All 24x24, all stroked with currentColor except the two that read better solid.
 */
const SVG_NS = 'http://www.w3.org/2000/svg';

const PATHS = {
  play: ['M8 5.5v13l11-6.5z'],
  pause: ['M9 5.5v13M15 5.5v13'],
  back10: ['M11 8H6.5V3.5', 'M6.9 8a7.5 7.5 0 1 1-1.4 5.2'],
  forward10: ['M13 8h4.5V3.5', 'M17.1 8a7.5 7.5 0 1 0 1.4 5.2'],
  volumeHigh: [
    'M4 9.5v5h3.5L12 18.5v-13L7.5 9.5H4z',
    'M16 9a4 4 0 0 1 0 6',
    'M18.5 6.5a7.5 7.5 0 0 1 0 11',
  ],
  volumeLow: ['M4 9.5v5h3.5L12 18.5v-13L7.5 9.5H4z', 'M16 9a4 4 0 0 1 0 6'],
  volumeMute: ['M4 9.5v5h3.5L12 18.5v-13L7.5 9.5H4z', 'M16.5 9.5l5 5M21.5 9.5l-5 5'],
  settings: [
    'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
    'M19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-1.8-.3 1.6 1.6 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1A1.6 1.6 0 0 0 9 19.4a1.6 1.6 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.6 1.6 0 0 0 .3-1.8 1.6 1.6 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1A1.6 1.6 0 0 0 4.6 9a1.6 1.6 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.6 1.6 0 0 0 1.8.3H9a1.6 1.6 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 1 1.5 1.6 1.6 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0-.3 1.8V9a1.6 1.6 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1z',
  ],
  pip: ['M3 5.5h18v13H3z', 'M12.5 12h7v5h-7z'],
  fullscreen: ['M8 3.5H3.5V8M16 3.5h4.5V8M8 20.5H3.5V16M16 20.5h4.5V16'],
  fullscreenExit: ['M3.5 8H8V3.5M20.5 8H16V3.5M3.5 16H8v4.5M20.5 16H16v4.5'],
  search: ['M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14z', 'M16.2 16.2 21 21'],
  copy: ['M9 9h10v12H9z', 'M15 9V3H5v12h4'],
  check: ['M4.5 12.5 9.5 17.5 19.5 7'],
  close: ['M6 6l12 12M18 6 6 18'],
  arrowLeft: ['M20 12H4', 'M10 6l-6 6 6 6'],
  keyboard: ['M3 6.5h18v11H3z', 'M7 10h.01M11 10h.01M15 10h.01M8 14h8'],
  spark: ['M12 3v4M12 17v4M3 12h4M17 12h4', 'M12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7z'],
};

/** Icons that read better as a filled shape than as a stroke. */
const FILLED = new Set(['play', 'volumeHigh', 'volumeLow', 'volumeMute']);

/**
 * One icon, as an <svg>. Decorative by default — the control that holds it carries the label.
 *
 * @param {string} name a key of PATHS
 * @param {string} [className]
 */
export function icon(name, className = 'btn__icon') {
  const svg = document.createElementNS(SVG_NS, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('focusable', 'false');
  if (className) {
    svg.setAttribute('class', className);
  }

  const filled = FILLED.has(name);
  for (const data of PATHS[name] ?? []) {
    const path = document.createElementNS(SVG_NS, 'path');
    path.setAttribute('d', data);
    if (filled) {
      path.setAttribute('fill', 'currentColor');
    } else {
      path.setAttribute('fill', 'none');
      path.setAttribute('stroke', 'currentColor');
      path.setAttribute('stroke-width', '1.8');
      path.setAttribute('stroke-linecap', 'round');
      path.setAttribute('stroke-linejoin', 'round');
    }
    svg.appendChild(path);
  }
  return svg;
}
