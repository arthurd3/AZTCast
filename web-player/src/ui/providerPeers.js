/**
 * Who is actually serving this torrent.
 *
 * A swarm is the one part of a download with nothing to show for it: the progress bar says how
 * much has arrived, and nothing says where any of it came from. This is that — one row per peer.
 *
 * Two callers, one renderer. The library page follows a running download and refreshes; the
 * provenance page draws the same table for something finished and never asks again. Only the
 * polling differs, so only the polling lives in the factory.
 *
 * Built from DOM nodes rather than innerHTML, like jobProgress and videoInfo: every field here
 * comes from a remote peer that chose what to send. The client name in particular is a string
 * that arbitrary software on the internet put in a handshake.
 */
import { listVideoPeers } from '../api/streamClient.js';
import { formatBytes, relativeTime } from './format.js';

const REFRESH_MS = 5000;

/** Enough to show the shape of a swarm without turning the page into a spreadsheet. */
const MAX_ROWS = 25;

const COLUMNS = [
  'Endereço',
  'Local',
  'Rede',
  'Cliente',
  'Estado',
  'Baixado',
  'Enviado',
  'Conectado',
  'Visto',
];

/**
 * The peer table as a detached element, ready to be placed.
 *
 * Peers that sent bytes come first, then peers that at least connected: an address a tracker named
 * and nobody ever reached is the least interesting row on the page, and it should not be at the top
 * of it.
 */
export function renderPeerTable(peers, { maxRows = MAX_ROWS } = {}) {
  const scroller = document.createElement('div');
  scroller.className = 'peers__scroll';

  const element = document.createElement('table');
  element.className = 'peers__table';
  // Explicit, and not redundant. Below 60rem peers.css lays every one of these out as a block
  // so each row can become a labelled card, and a table element laid out as a block loses the
  // implicit role its tag would otherwise carry — a screen reader stops seeing rows and cells
  // and starts seeing loose text. These say the roles out loud so the narrow layout keeps the
  // semantics the wide one gets for free.
  element.setAttribute('role', 'table');

  const head = document.createElement('thead');
  head.setAttribute('role', 'rowgroup');
  const headRow = document.createElement('tr');
  headRow.setAttribute('role', 'row');
  COLUMNS.forEach((label) => {
    const heading = document.createElement('th');
    heading.scope = 'col';
    heading.setAttribute('role', 'columnheader');
    heading.textContent = label;
    headRow.appendChild(heading);
  });
  head.appendChild(headRow);

  const body = document.createElement('tbody');
  body.setAttribute('role', 'rowgroup');
  [...peers]
    .sort(
      (left, right) =>
        right.bytesDownloaded - left.bytesDownloaded || right.timesConnected - left.timesConnected,
    )
    .slice(0, maxRows)
    .forEach((peer) => body.appendChild(row(peer)));

  element.append(head, body);
  scroller.appendChild(element);
  return scroller;
}

/** `71 pares encontrados · 10 conectados · 9 semeando`. */
export function peerSummaryText(peers) {
  const connected = peers.filter((peer) => peer.timesConnected > 0).length;
  const seeders = peers.filter((peer) => peer.role === 'SEEDER').length;
  return (
    `${peers.length} ${peers.length === 1 ? 'par encontrado' : 'pares encontrados'}` +
    ` · ${connected} conectado${connected === 1 ? '' : 's'} · ${seeders} semeando`
  );
}

function row(peer) {
  const element = document.createElement('tr');
  element.setAttribute('role', 'row');
  if (peer.timesConnected > 0) {
    element.className = 'peers__row--connected';
  }

  const cells = [
    // Address, plus a marker when this same address turned up in other downloads.
    cell(`${peer.ipAddress}:${peer.port}`, 'peers__address', recurrenceBadge(peer)),
    cell(place(peer)),
    // A datacenter or VPN exit is a machine, not a household, and the row should say so.
    cell(peer.network ?? '—', null, hostingBadge(peer)),
    cell(peer.client ?? '—', null, capabilities(peer)),
    cell(state(peer)),
    cell(downloaded(peer)),
    cell(peer.bytesUploaded > 0 ? formatBytes(peer.bytesUploaded) : '—'),
    cell(peer.connectedSeconds > 0 ? duration(peer.connectedSeconds) : '—'),
    cell(relativeTime(peer.lastSeen)),
  ];

  // Same order as COLUMNS by construction. The label rides along on the cell because the card
  // layout has no header row to read it from — peers.css prints it from ::before.
  cells.forEach((node, index) => {
    node.dataset.label = COLUMNS[index];
    element.appendChild(node);
  });
  return element;
}

function cell(text, className, ...extras) {
  const node = document.createElement('td');
  node.setAttribute('role', 'cell');
  if (className) {
    node.className = className;
  }
  node.append(text);
  extras.filter(Boolean).forEach((extra) => node.append(' ', extra));
  return node;
}

function badge(text, modifier, title) {
  const element = document.createElement('span');
  element.className = `peer-badge peer-badge--${modifier}`;
  element.textContent = text;
  if (title) {
    element.title = title;
  }
  return element;
}

/**
 * How much this peer sent, and how fast on average.
 *
 * The rate is only shown for a peer that connected exactly once. Bytes are merged with MAX across
 * reconnects while the seconds accumulate, so for anything else the two do not divide into each
 * other and the quotient would quietly understate the speed. One connection is the ordinary case;
 * the rest simply say how much, which is true either way.
 */
function downloaded(peer) {
  if (!(peer.bytesDownloaded > 0)) {
    return '—';
  }
  const total = formatBytes(peer.bytesDownloaded);
  if (peer.timesConnected !== 1 || !(peer.connectedSeconds > 0)) {
    return total;
  }
  return `${total} · ${formatBytes(peer.bytesDownloaded / peer.connectedSeconds)}/s`;
}

function hostingBadge(peer) {
  return peer.networkKind === 'HOSTING'
    ? badge(
        'datacenter',
        'hosting',
        'Provável datacenter ou saída de VPN — deduzido do nome do operador, não confirmado.',
      )
    : null;
}

/**
 * What the peer announced it can do, before any data moved.
 *
 * Plain text rather than a row of pills. Almost every modern client supports all five, so as badges
 * they were five bordered boxes repeated identically down the table — a lot of width spent saying
 * the same thing, which pushed the columns that do differ off the edge. Quiet text keeps the detail
 * for anyone reading a row closely and gives the space back to the numbers.
 */
function capabilities(peer) {
  const flags = peer.capabilities ?? [];
  if (!flags.length) {
    return null;
  }
  const element = document.createElement('span');
  element.className = 'peer-caps';
  element.textContent = flags
    .map((flag) => CAPABILITY_LABELS[flag] ?? flag.toLowerCase())
    .join(' ');
  element.title = flags.map((flag) => CAPABILITY_TITLES[flag] ?? flag).join('\n');
  return element;
}

const CAPABILITY_LABELS = {
  DHT: 'dht',
  EXT: 'ext',
  FAST: 'fast',
  PEX: 'pex',
  METADATA: 'md',
};

const CAPABILITY_TITLES = {
  DHT: 'Participa da DHT',
  EXT: 'Protocolo de extensão (BEP-10)',
  FAST: 'Fast extension (BEP-6)',
  PEX: 'Troca de pares (ut_pex)',
  METADATA: 'Troca de metadados (ut_metadata)',
};

function recurrenceBadge(peer) {
  return peer.videosServed > 1
    ? badge(`${peer.videosServed} vídeos`, 'recurring', 'Este endereço serviu mais de um download.')
    : null;
}

/** A span of seconds as `4m 09s`, matching the elapsed clock on the library page. */
function duration(seconds) {
  if (seconds < 60) {
    return `${seconds}s`;
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return `${minutes}m ${String(seconds % 60).padStart(2, '0')}s`;
  }
  return `${Math.floor(minutes / 60)}h ${String(minutes % 60).padStart(2, '0')}m`;
}

/**
 * The live view, for a download in progress.
 *
 * Absent rather than empty when the provider log is off. The endpoint 404s in that case, and a
 * table that is always there but never has rows reads as broken rather than as disabled.
 */
export function createProviderPeers(container) {
  let timer = null;
  let videoId = null;

  function clear() {
    stop();
    videoId = null;
    container.replaceChildren();
    container.classList.add('hidden');
  }

  function stop() {
    if (timer !== null) {
      window.clearTimeout(timer);
      timer = null;
    }
  }

  /** Starts following {@code id}'s swarm, refreshing until frozen or cleared. */
  function follow(id) {
    if (videoId === id) {
      return;
    }
    stop();
    videoId = id;
    refresh();
  }

  /**
   * Keeps the table but stops asking for it.
   *
   * Called when the download ends: the rows are still worth reading — they are the record of who
   * served the video — but the swarm is disconnected, so re-fetching identical rows every five
   * seconds for the length of a transcode is pure noise.
   */
  function freeze() {
    stop();
  }

  async function refresh() {
    const following = videoId;
    let peers;
    try {
      peers = await listVideoPeers(following);
    } catch {
      // 404 means the log is off; anything else is transient. Either way there is nothing to
      // draw, and a swarm view is not worth an error banner over the download it decorates.
      clear();
      return;
    }
    if (following !== videoId) {
      return;
    }
    render(peers);
    timer = window.setTimeout(refresh, REFRESH_MS);
  }

  function render(peers) {
    if (!peers.length) {
      container.replaceChildren();
      container.classList.add('hidden');
      return;
    }
    container.classList.remove('hidden');

    const heading = document.createElement('h3');
    heading.className = 'peers__title';
    heading.textContent = 'Fornecedores';

    const summary = document.createElement('p');
    summary.className = 'peers__summary';
    summary.textContent = peerSummaryText(peers);

    container.replaceChildren(heading, summary, renderPeerTable(peers));
  }

  clear();
  return { follow, freeze, clear };
}

/** Country and city, as far as the database knew. An em dash when it had no entry. */
function place(peer) {
  const parts = [peer.city, peer.country ?? peer.countryCode].filter(Boolean);
  return parts.length ? parts.join(', ') : '—';
}

function state(peer) {
  if (peer.role === 'SEEDER') {
    return 'Semeando';
  }
  if (peer.role === 'LEECHER') {
    return Number.isFinite(peer.completePercent) ? `Baixando ${peer.completePercent}%` : 'Baixando';
  }
  return peer.timesConnected > 0 ? 'Conectado' : 'Descoberto';
}
