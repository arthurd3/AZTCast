import './styles/main.css';
import Hls from 'hls.js';
import { masterPlaylistUrl } from './api/streamClient.js';
import { createHlsPlayer } from './player/hlsPlayer.js';
import { createStatusBanner } from './ui/statusBanner.js';

/**
 * A harness for diagnosing playback failures, kept from the original hls-test.html.
 *
 * It is not decoration: it is the only place that inspects what the server actually
 * advertises versus what the browser can actually play, which is the question you need
 * answered when segments fail to append. The codec/MIME checklist that used to live in
 * this file now lives in docs/troubleshooting-hls.md.
 *
 * The baked-in video UUID the original shipped with has been removed.
 */
const videoIdInput = document.getElementById('videoId');
const videoElement = document.getElementById('video');
const logElement = document.getElementById('log');
const status = createStatusBanner(document.getElementById('status'));

function log(message, level = 'info') {
  const line = document.createElement('div');
  line.className = `level-${level}`;
  line.textContent = `${new Date().toISOString().slice(11, 23)}  ${message}`;
  logElement.append(line);
  logElement.scrollTop = logElement.scrollHeight;
}

function requireVideoId() {
  const videoId = videoIdInput.value.trim();
  if (!videoId) {
    status.show('Informe um Video ID.', 'error');
    return null;
  }
  return videoId;
}

/** Fetches the master playlist and reports what it claims, then whether the browser agrees. */
async function inspect() {
  const videoId = requireVideoId();
  if (!videoId) {
    return;
  }
  const url = masterPlaylistUrl(videoId);
  status.show('Inspecionando…', 'loading');
  log(`GET ${url}`);

  let manifest;
  try {
    const response = await fetch(url);
    log(`  ${response.status} ${response.headers.get('content-type') ?? '(sem content-type)'}`);
    if (!response.ok) {
      status.show(`Master playlist indisponível (${response.status}).`, 'error');
      return;
    }
    manifest = await response.text();
  } catch (error) {
    log(`  falhou: ${error.message}`, 'error');
    status.show('Não foi possível buscar o master playlist.', 'error');
    return;
  }

  const isMaster = /#EXT-X-STREAM-INF/i.test(manifest);
  log(`  tipo: ${isMaster ? 'master playlist' : 'media playlist'}`);

  const codecs = [...manifest.matchAll(/CODECS="([^"]+)"/gi)].map((match) => match[1]);
  if (codecs.length === 0) {
    log(
      '  nenhum atributo CODECS anunciado — o player terá que sondar o primeiro segmento',
      'warn',
    );
  }
  for (const codec of codecs) {
    const mimeType = `video/mp4; codecs="${codec}"`;
    const supported = window.MediaSource?.isTypeSupported(mimeType) ?? false;
    log(`  CODECS="${codec}" -> isTypeSupported: ${supported}`, supported ? 'ok' : 'error');
  }

  await inspectFirstVariant(manifest, url);
  status.show('Inspeção concluída.', 'success');
}

/** Follows the first variant and HEADs its first segment, to see the MIME the server sends. */
async function inspectFirstVariant(manifest, masterUrl) {
  const variant = manifest.split('\n').find((line) => line.trim() && !line.startsWith('#'));
  if (!variant) {
    return;
  }
  const variantUrl = new URL(variant.trim(), masterUrl).href;
  log(`GET ${variantUrl}`);

  const response = await fetch(variantUrl);
  log(`  ${response.status} ${response.headers.get('content-type') ?? '(sem content-type)'}`);
  if (!response.ok) {
    return;
  }

  const media = await response.text();
  const segment = media.split('\n').find((line) => line.trim() && !line.startsWith('#'));
  if (!segment) {
    log('  variante sem segmentos', 'warn');
    return;
  }

  const segmentUrl = new URL(segment.trim(), variantUrl).href;
  const head = await fetch(segmentUrl, { method: 'HEAD' });
  const contentType = head.headers.get('content-type') ?? '(sem content-type)';
  log(`HEAD ${segmentUrl}`);
  // A .ts served as application/octet-stream is the classic cause of bufferAppend errors.
  const looksRight = /video\/(mp2t|mp4|iso\.segment)/.test(contentType);
  log(`  ${head.status} ${contentType}`, looksRight ? 'ok' : 'error');
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
      status.show('Reproduzindo.', 'success');
      videoElement.play().catch(() => log('autoplay bloqueado — clique no vídeo', 'warn'));
    },
    onLevelSwitched: (level) => log(`nível -> ${level}`),
    onError: (message, kind) => {
      log(message, kind === 'error' ? 'error' : 'warn');
      status.show(message, kind);
    },
  });

  if (!player.usesMediaSource()) {
    log('MediaSource indisponível; usando player nativo', 'warn');
    player.loadNative(url);
    return;
  }
  log(`carregando ${url}`);
  player.load(url);
}

document.getElementById('inspectBtn').addEventListener('click', inspect);
document.getElementById('playBtn').addEventListener('click', play);
document.getElementById('clearBtn').addEventListener('click', () => logElement.replaceChildren());

log(`hls.js ${Hls.version} — MediaSource ${window.MediaSource ? 'disponível' : 'indisponível'}`);
