import './styles/main.css';
import Hls from 'hls.js';
import { masterPlaylistUrl } from './api/streamClient.js';
import { createControls } from './player/controls.js';
import { createHlsPlayer } from './player/hlsPlayer.js';
import { mountCosmos } from './ui/cosmos.js';
import { createStatusBanner } from './ui/statusBanner.js';

/**
 * A harness for diagnosing playback failures, kept from the original hls-test.html.
 *
 * It is not decoration: it is the only place that inspects what the server actually
 * advertises versus what the browser can actually play, which is the question you need
 * answered when segments fail to append. The codec/MIME checklist that used to live in
 * this file now lives in docs/troubleshooting-hls.md.
 *
 * The inspection logic below is unchanged; what is new is that its findings are also rendered
 * as chips, so "does this browser support the advertised codec" is answerable at a glance
 * instead of by reading a wall of log lines.
 */
const videoIdInput = document.getElementById('videoId');
const videoElement = document.getElementById('video');
const playerElement = document.getElementById('player');
const logElement = document.getElementById('log');
const reportElement = document.getElementById('report');
const autoScroll = document.getElementById('autoScroll');
const status = createStatusBanner(document.getElementById('status'));

mountCosmos();
const controls = createControls(playerElement, videoElement);

function log(message, level = 'info') {
  const line = document.createElement('div');
  line.className = `log__line log__line--${level}`;

  const time = document.createElement('span');
  time.className = 'log__time';
  time.textContent = new Date().toISOString().slice(11, 23);

  const text = document.createElement('span');
  text.className = 'log__message';
  text.textContent = message;

  line.append(time, text);
  logElement.append(line);

  // Opt-out, because following a live log is the usual case but reading back through one
  // while it is still being written is impossible if it keeps yanking you to the bottom.
  if (autoScroll.checked) {
    logElement.scrollTop = logElement.scrollHeight;
  }
}

/** One fact in the report grid: a title and either plain values or chips. */
function card(title, children) {
  const element = document.createElement('div');
  element.className = 'report__card';

  const heading = document.createElement('p');
  heading.className = 'report__title';
  heading.textContent = title;

  const rows = document.createElement('div');
  rows.className = 'report__rows';
  rows.append(...children);

  element.append(heading, rows);
  return element;
}

function value(text) {
  const element = document.createElement('span');
  element.className = 'report__value';
  element.textContent = text;
  return element;
}

/** @param {'ok'|'bad'|'warn'|null} kind */
function chip(text, kind) {
  const element = document.createElement('span');
  element.className = kind ? `chip chip--${kind}` : 'chip';
  element.textContent = text;
  return element;
}

function requireVideoId() {
  const videoId = videoIdInput.value.trim();
  if (!videoId) {
    videoIdInput.setAttribute('aria-invalid', 'true');
    status.show('Informe um Video ID.', 'error');
    videoIdInput.focus();
    return null;
  }
  videoIdInput.removeAttribute('aria-invalid');
  return videoId;
}

/** Fetches the master playlist and reports what it claims, then whether the browser agrees. */
async function inspect() {
  const videoId = requireVideoId();
  if (!videoId) {
    return;
  }
  // Absolute, deliberately. masterPlaylistUrl() is root-relative whenever VITE_API_BASE_URL is
  // unset — which is the default and what production runs — and `new URL(child, base)` throws
  // on a relative base. That threw here on every inspection, unhandled, which is why the
  // segment MIME check below never ran and the status sat on "Inspecionando…" forever. It is
  // the one check this page exists for: docs/troubleshooting-hls.md sends you here to find a
  // .m4s being served as application/octet-stream.
  const url = new URL(masterPlaylistUrl(videoId), window.location.href).href;
  status.show('Inspecionando…', 'loading');
  reportElement.replaceChildren();
  log(`GET ${url}`);

  let manifest;
  let contentType;
  try {
    const response = await fetch(url);
    contentType = response.headers.get('content-type') ?? '(sem content-type)';
    log(`  ${response.status} ${contentType}`);
    if (!response.ok) {
      reportElement.replaceChildren(
        card('Master playlist', [chip(`HTTP ${response.status}`, 'bad')]),
      );
      status.show(`Master playlist indisponível (${response.status}).`, 'error');
      return;
    }
    manifest = await response.text();
  } catch (error) {
    log(`  falhou: ${error.message}`, 'error');
    reportElement.replaceChildren(card('Master playlist', [chip('Inalcançável', 'bad')]));
    status.show('Não foi possível buscar o master playlist.', 'error');
    return;
  }

  const cards = [];

  const isMaster = /#EXT-X-STREAM-INF/i.test(manifest);
  log(`  tipo: ${isMaster ? 'master playlist' : 'media playlist'}`);
  cards.push(
    card('Master playlist', [
      chip(isMaster ? 'Master' : 'Media playlist', isMaster ? 'ok' : 'warn'),
      value(contentType),
    ]),
  );

  const codecs = [...manifest.matchAll(/CODECS="([^"]+)"/gi)].map((match) => match[1]);
  if (codecs.length === 0) {
    log(
      '  nenhum atributo CODECS anunciado — o player terá que sondar o primeiro segmento',
      'warn',
    );
    cards.push(card('Codecs anunciados', [chip('Nenhum declarado', 'warn')]));
  } else {
    const rows = [];
    for (const codec of codecs) {
      const mimeType = `video/mp4; codecs="${codec}"`;
      const supported = window.MediaSource?.isTypeSupported(mimeType) ?? false;
      log(`  CODECS="${codec}" -> isTypeSupported: ${supported}`, supported ? 'ok' : 'error');
      rows.push(chip(codec, supported ? 'ok' : 'bad'));
    }
    cards.push(card('Codecs anunciados', rows));
  }

  let segment = null;
  try {
    segment = await inspectFirstVariant(manifest, url);
  } catch (error) {
    log(`  falhou ao seguir a primeira variante: ${error.message}`, 'error');
    cards.push(card('Primeiro segmento', [chip('Não verificado', 'bad')]));
  }
  if (segment) {
    cards.push(
      card('Primeiro segmento', [
        chip(segment.contentType, segment.looksRight ? 'ok' : 'bad'),
        value(`HTTP ${segment.status}`),
      ]),
    );
  }

  reportElement.replaceChildren(...cards);
  status.show(
    segment ? 'Inspeção concluída.' : 'Inspeção concluída com falhas.',
    segment ? 'success' : 'error',
  );
}

/** Follows the first variant and HEADs its first segment, to see the MIME the server sends. */
async function inspectFirstVariant(manifest, masterUrl) {
  const variant = manifest.split('\n').find((line) => line.trim() && !line.startsWith('#'));
  if (!variant) {
    return null;
  }
  const variantUrl = new URL(variant.trim(), masterUrl).href;
  log(`GET ${variantUrl}`);

  const response = await fetch(variantUrl);
  log(`  ${response.status} ${response.headers.get('content-type') ?? '(sem content-type)'}`);
  if (!response.ok) {
    return null;
  }

  const media = await response.text();
  const segment = media.split('\n').find((line) => line.trim() && !line.startsWith('#'));
  if (!segment) {
    log('  variante sem segmentos', 'warn');
    return null;
  }

  const segmentUrl = new URL(segment.trim(), variantUrl).href;
  const head = await fetch(segmentUrl, { method: 'HEAD' });
  const contentType = head.headers.get('content-type') ?? '(sem content-type)';
  log(`HEAD ${segmentUrl}`);
  // A .ts served as application/octet-stream is the classic cause of bufferAppend errors.
  const looksRight = /video\/(mp2t|mp4|iso\.segment)/.test(contentType);
  log(`  ${head.status} ${contentType}`, looksRight ? 'ok' : 'error');
  return { status: head.status, contentType, looksRight };
}

function play() {
  const videoId = requireVideoId();
  if (!videoId) {
    return;
  }
  const url = masterPlaylistUrl(videoId);

  const player = createHlsPlayer(videoElement, {
    onManifestParsed: () => {
      log(`manifest parseado, ${player.levels().length} nível(is)`, 'ok');
      player.levels().forEach((level, index) => {
        log(
          `  L${index}: ${level.width}x${level.height} vc=${level.videoCodec} ac=${level.audioCodec}`,
        );
      });
      controls.setQuality(player.levels(), player.currentLevel(), player.loadLevel());
      status.show('Reproduzindo.', 'success');
      videoElement.play().catch(() => log('autoplay bloqueado — clique no vídeo', 'warn'));
    },
    onLevelSwitched: (level) => {
      log(`nível -> ${level}`);
      controls.setQuality(player.levels(), player.currentLevel(), level);
    },
    onError: (message, kind) => {
      log(message, kind === 'error' ? 'error' : 'warn');
      status.show(message, kind);
    },
  });

  controls.onSelectLevel = (level) => {
    player.selectLevel(level);
    controls.setQuality(player.levels(), player.currentLevel(), player.loadLevel());
  };

  if (!player.usesMediaSource()) {
    log('MediaSource indisponível; usando player nativo', 'warn');
    player.loadNative(url);
    return;
  }
  log(`carregando ${url}`);
  player.load(url);
}

/** The environment banner: what this browser brings to the problem, before any video is named. */
function describeEnvironment() {
  const mediaSource = Boolean(window.MediaSource);
  const nativeHls = videoElement.canPlayType('application/vnd.apple.mpegurl') !== '';

  document
    .getElementById('environment')
    .replaceChildren(
      chip(`hls.js ${Hls.version}`, null),
      chip(
        `MediaSource ${mediaSource ? 'disponível' : 'indisponível'}`,
        mediaSource ? 'ok' : 'bad',
      ),
      chip(`HLS nativo ${nativeHls ? 'sim' : 'não'}`, nativeHls ? 'ok' : null),
      chip(
        Hls.isSupported() ? 'hls.js suportado' : 'hls.js não suportado',
        Hls.isSupported() ? 'ok' : 'bad',
      ),
    );
}

document.getElementById('inspectBtn').addEventListener('click', inspect);
document.getElementById('playBtn').addEventListener('click', play);
document.getElementById('clearBtn').addEventListener('click', () => {
  logElement.replaceChildren();
  reportElement.replaceChildren();
});
document.getElementById('copyLogBtn').addEventListener('click', async () => {
  try {
    await navigator.clipboard.writeText(logElement.textContent);
    status.show('Log copiado.', 'success');
  } catch {
    status.show('O navegador não permitiu copiar.', 'error');
  }
});
videoIdInput.addEventListener('keydown', (event) => {
  if (event.key === 'Enter') {
    inspect();
  }
});

describeEnvironment();
