import './styles/main.css';
import {
  getProviderSummary,
  listVideoPeers,
  playerPageUrl,
  posterUrl,
} from './api/streamClient.js';
import { formatBytes, relativeTime } from './ui/format.js';
import { mountCosmos } from './ui/cosmos.js';
import { peerSummaryText, renderPeerTable } from './ui/providerPeers.js';
import { createStatusBanner } from './ui/statusBanner.js';
import { createWorldMap } from './ui/worldMap.js';

/**
 * The provenance page: what has been downloaded, and where it came from.
 *
 * The provider log has been recording this all along and there was nowhere to read it — the live
 * table under the progress bar vanishes when the download does, while the rows survive for a month.
 * This is the page that reads them.
 *
 * One request draws the whole thing. The totals, the video list and the map all come out of a
 * single aggregate, because they are three views of one query and fanning out per video would be
 * slower and would say the same thing.
 */
mountCosmos();

const status = createStatusBanner(document.getElementById('status'));
const totalsElement = document.getElementById('totals');
const captionElement = document.getElementById('mapCaption');
const videosElement = document.getElementById('videos');
const breakdownElement = document.getElementById('breakdown');
const map = createWorldMap(document.getElementById('map'));

/** The video currently filtering the map, or null for everything. */
let selectedVideoId = null;

load();

async function load() {
  let summary;
  try {
    summary = await getProviderSummary(selectedVideoId);
  } catch (error) {
    // A 404 is the provider log being switched off, which is a configuration state rather than a
    // failure — so it gets an explanation instead of an error.
    status.show(
      error.status === 404
        ? 'O registro de fornecedores está desligado. Ligue aztcast.streaming.providers.enabled para começar a gravar.'
        : error.message,
      error.status === 404 ? 'loading' : 'error',
    );
    return;
  }

  status.hide();
  renderTotals(summary);
  renderCaption(summary);
  renderBreakdown(summary.distributions);
  map.render(summary.places);

  // Only on the first load: re-rendering the list on every filter change would collapse whichever
  // video the reader had just expanded.
  if (!videosElement.childElementCount) {
    renderVideos(summary.videos);
  }
}

function renderTotals(summary) {
  const { totals } = summary;
  const entries = [
    [totals.videos, 'vídeos'],
    [totals.peers, 'pares'],
    [totals.countries, 'países'],
    [totals.networks, 'redes'],
  ].map(([value, label]) => stat(String(value), label));

  entries.push(stat(formatBytes(totals.bytesDownloaded), 'recebidos do enxame'));

  // Both are readings of the swarm rather than counts of it, so they sit after the plain totals.
  if (totals.hostingPeers > 0) {
    entries.push(
      stat(
        `${Math.round((totals.hostingPeers / totals.peers) * 100)}%`,
        'prováveis datacenter/VPN',
      ),
    );
  }
  if (totals.recurringPeers > 0) {
    entries.push(stat(String(totals.recurringPeers), 'pares em mais de um vídeo'));
  }

  // The one fact here that is about us rather than about them, and the only direct evidence
  // available of whether outbound traffic is masked: a remote peer reporting what it saw.
  if (summary.addressPeersSee) {
    entries.push(stat(summary.addressPeersSee, 'como os pares te veem', 'atlas__stat--self'));
  }

  totalsElement.replaceChildren(...entries);
}

function stat(value, label, modifier) {
  const element = document.createElement('div');
  element.className = modifier ? `atlas__stat ${modifier}` : 'atlas__stat';

  const number = document.createElement('strong');
  number.className = 'atlas__stat-value';
  number.textContent = value;

  const caption = document.createElement('span');
  caption.className = 'atlas__stat-label';
  caption.textContent = label;

  element.append(number, caption);
  return element;
}

/**
 * Three rankings: which software, which countries, which networks.
 *
 * Bars are divs with a width. A chart library would be a second dependency for something that is
 * one number per row, on a page that already carries a map.
 */
function renderBreakdown(distributions) {
  if (!distributions) {
    return;
  }
  const groups = [
    ['Clientes', distributions.clients],
    ['Países', distributions.countries],
    ['Redes', distributions.networks],
  ].filter(([, slices]) => slices?.length);

  if (!groups.length) {
    breakdownElement.replaceChildren();
    return;
  }
  breakdownElement.replaceChildren(...groups.map(([title, slices]) => group(title, slices)));
}

function group(title, slices) {
  const section = document.createElement('div');
  section.className = 'breakdown__group';

  const heading = document.createElement('h3');
  heading.className = 'breakdown__title';
  heading.textContent = title;
  section.appendChild(heading);

  // Against the leader, not against the total: the tail would otherwise be a row of invisible
  // slivers, and the question these answer is "what dominates", not "what fraction exactly".
  const busiest = Math.max(...slices.map((slice) => slice.peers));

  slices.forEach((slice) => {
    const row = document.createElement('div');
    row.className = 'breakdown__row';

    const label = document.createElement('span');
    label.className = 'breakdown__label';
    label.textContent = slice.label;
    label.title = slice.label;

    const track = document.createElement('span');
    track.className = 'breakdown__track';
    const fill = document.createElement('span');
    fill.className = 'breakdown__fill';
    fill.style.width = `${Math.max(2, (slice.peers / busiest) * 100)}%`;
    track.appendChild(fill);

    const value = document.createElement('span');
    value.className = 'breakdown__value';
    value.textContent = String(slice.peers);

    row.append(label, track, value);
    section.appendChild(row);
  });
  return section;
}

function renderCaption(summary) {
  const mappable = summary.places.length;
  const scope = selectedVideoId ? 'deste vídeo' : 'de todos os vídeos';

  if (!mappable) {
    captionElement.textContent = summary.totals.peers
      ? 'Nenhum par pôde ser localizado. Configure uma base GeoIP para desenhar o mapa.'
      : 'Nada gravado ainda.';
    return;
  }
  captionElement.textContent =
    `${mappable} ${mappable === 1 ? 'local' : 'locais'} ${scope}. ` +
    'Cada círculo é a área dentro da qual o endereço deve estar, não um ponto exato.';
}

function renderVideos(videos) {
  if (!videos.length) {
    const empty = document.createElement('p');
    empty.className = 'muted';
    empty.textContent = 'Nenhum download registrado ainda.';
    videosElement.replaceChildren(empty);
    return;
  }
  videosElement.replaceChildren(...videos.map(videoCard));
}

function videoCard(video) {
  const card = document.createElement('article');
  card.className = 'provenance';

  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'provenance__head';
  button.setAttribute('aria-expanded', 'false');

  const poster = document.createElement('div');
  poster.className = 'provenance__poster';
  const posterSrc = posterUrl(video);
  if (posterSrc) {
    const image = document.createElement('img');
    image.src = posterSrc;
    image.alt = '';
    image.loading = 'lazy';
    poster.appendChild(image);
  }

  const text = document.createElement('div');
  text.className = 'provenance__text';

  const title = document.createElement('span');
  title.className = 'provenance__title';
  title.textContent = video.title ?? video.videoId;

  const meta = document.createElement('span');
  meta.className = 'provenance__meta';
  meta.textContent =
    `${video.peerCount} ${video.peerCount === 1 ? 'par' : 'pares'} · ` +
    `${video.connectedCount} conectado${video.connectedCount === 1 ? '' : 's'} · ` +
    `${video.seederCount} semeando · ` +
    `${formatBytes(video.bytesDownloaded)} · ${relativeTime(video.lastSeen)}`;

  text.append(title, meta);

  // Not in the catalogue on disk. That covers a video the reaper took — peer rows outlive media
  // by three weeks, so this is the normal end state of an old ingestion — and one whose transcode
  // never finished. "Indisponível" is true of both; "removida" would assert the first.
  if (!video.available) {
    const gone = document.createElement('span');
    gone.className = 'badge provenance__gone';
    gone.textContent = 'mídia indisponível';
    text.appendChild(gone);
  }

  button.append(poster, text);

  const panel = document.createElement('div');
  panel.className = 'provenance__peers hidden';

  button.addEventListener('click', () => toggle(video, button, panel));
  card.append(button, panel);

  if (video.available) {
    const watch = document.createElement('a');
    watch.className = 'btn btn--ghost btn--sm provenance__watch';
    watch.href = playerPageUrl(video.videoId);
    watch.textContent = 'Assistir';
    card.appendChild(watch);
  }
  return card;
}

async function toggle(video, button, panel) {
  const expanded = button.getAttribute('aria-expanded') === 'true';

  document.querySelectorAll('.provenance__head[aria-expanded="true"]').forEach((other) => {
    if (other !== button) {
      other.setAttribute('aria-expanded', 'false');
      other.parentElement.querySelector('.provenance__peers').classList.add('hidden');
    }
  });

  if (expanded) {
    button.setAttribute('aria-expanded', 'false');
    panel.classList.add('hidden');
    selectedVideoId = null;
    await load();
    return;
  }

  button.setAttribute('aria-expanded', 'true');
  panel.classList.remove('hidden');
  selectedVideoId = video.videoId;

  // Both halves in parallel: the map filter and the peer table answer the same question about the
  // same video, and neither depends on the other.
  const [peers] = await Promise.all([peersFor(video.videoId), load()]);
  if (selectedVideoId !== video.videoId) {
    return;
  }
  renderPeers(panel, peers);
}

async function peersFor(videoId) {
  try {
    return await listVideoPeers(videoId);
  } catch {
    return [];
  }
}

function renderPeers(panel, peers) {
  if (!peers.length) {
    const empty = document.createElement('p');
    empty.className = 'muted';
    empty.textContent = 'Nenhum par gravado para este vídeo.';
    panel.replaceChildren(empty);
    return;
  }
  const summary = document.createElement('p');
  summary.className = 'peers__summary';
  summary.textContent = peerSummaryText(peers);

  panel.replaceChildren(summary, renderPeerTable(peers, { maxRows: 100 }));
}
