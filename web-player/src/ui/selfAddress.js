import { icon } from './icons.js';

/**
 * The one tile on the provenance page that is about the reader rather than about the swarm.
 *
 * It holds `addressPeersSee` — this machine's public address, as a remote peer reported it in the
 * `yourip` field of the BEP-10 extended handshake. The value is not a secret: every peer in the
 * swarm already has it, which is the whole point of ADR-0015. But this is the page you open to
 * check whether the tunnel is working, which makes it the page most likely to be on screen during
 * a screen share or in a screenshot — and rendering the address unprompted was never a decision
 * anyone made. So it starts masked and reveals on a deliberate press. See ADR-0029.
 *
 * The tile is rebuilt on every render and therefore starts masked every time, including when
 * expanding a video re-runs the page's load(). That is the decided behaviour, not an oversight.
 */

/*
 * Fixed length, never derived from the address: a run as long as the value would tell anyone
 * looking whether this machine is on IPv4 or IPv6, which is most of what the mask is hiding.
 */
const MASK = '••••••••••••';

const LABEL = 'como os pares te veem';
const TOGGLE_LABEL = 'Mostrar o endereço';

/**
 * The masked address tile, ready to append. Never call this with an empty address — a tile
 * showing a masked run with nothing behind it reads as "we know it and will not tell you".
 *
 * @param {string} address the address a peer reported seeing
 * @returns {HTMLElement}
 */
export function renderSelfAddress(address) {
  const element = document.createElement('div');
  element.className = 'atlas__stat atlas__stat--self';

  const row = document.createElement('div');
  row.className = 'atlas__self';

  const value = document.createElement('strong');
  value.className = 'atlas__stat-value is-masked';
  value.textContent = MASK;
  // The glyph run is decoration; a screen reader reading out twelve bullets is worse than
  // silence. The state text below is what carries the meaning.
  value.setAttribute('aria-hidden', 'true');

  const toggle = document.createElement('button');
  toggle.type = 'button';
  toggle.className = 'btn btn--icon atlas__self-toggle';
  // The accessible name stays put and aria-pressed carries the state — swapping both at once is
  // the mistake, because a dynamic accessible name is what screen readers handle badly.
  toggle.setAttribute('aria-label', TOGGLE_LABEL);
  toggle.title = TOGGLE_LABEL;
  toggle.setAttribute('aria-pressed', 'false');
  toggle.append(icon('eye', 'btn__icon'));

  const state = document.createElement('span');
  state.className = 'visually-hidden';
  // Populated before this joins the document, so it announces the toggle rather than the load.
  state.setAttribute('role', 'status');
  state.textContent = 'Endereço oculto';

  const caption = document.createElement('span');
  caption.className = 'atlas__stat-label';
  caption.textContent = LABEL;

  const link = document.createElement('a');
  link.className = 'atlas__stat-link';
  link.href = '/localizacao.html';
  link.textContent = 'Como isto é descoberto';

  let revealed = false;
  toggle.addEventListener('click', () => {
    revealed = !revealed;
    toggle.setAttribute('aria-pressed', revealed ? 'true' : 'false');
    toggle.classList.toggle('btn--active', revealed);
    toggle.replaceChildren(icon(revealed ? 'eyeOff' : 'eye', 'btn__icon'));

    value.textContent = revealed ? address : MASK;
    value.classList.toggle('is-masked', !revealed);
    if (revealed) {
      value.removeAttribute('aria-hidden');
    } else {
      value.setAttribute('aria-hidden', 'true');
    }
    state.textContent = revealed ? 'Endereço visível' : 'Endereço oculto';
  });

  row.append(value, toggle);
  element.append(row, state, caption, link);
  return element;
}
