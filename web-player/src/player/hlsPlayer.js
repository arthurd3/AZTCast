import Hls from 'hls.js';

/**
 * Maximum automatic recoveries before giving up on a media error.
 *
 * This is what `mediaErrorRecoveryCount` was for in the original player: it was declared
 * and reset, but never incremented and never read — the remains of a retry loop that had
 * been removed. The recovery ladder below is the one the diagnostics page used, which was
 * strictly better than production's: production called destroy() on every fatal error and
 * left the load button disabled, so the only way out was reloading the page.
 */
const MAX_MEDIA_RECOVERIES = 2;
const MAX_NETWORK_RECOVERIES = 3;

/** Wraps hls.js (or Safari's native HLS) behind one small interface. */
export function createHlsPlayer(videoElement, { onManifestParsed, onLevelSwitched, onError }) {
  let hls = null;
  let mediaRecoveries = 0;
  let networkRecoveries = 0;

  function destroy() {
    if (hls) {
      hls.destroy();
      hls = null;
    }
  }

  function handleFatalError(data) {
    switch (data.type) {
      case Hls.ErrorTypes.NETWORK_ERROR:
        if (networkRecoveries < MAX_NETWORK_RECOVERIES) {
          networkRecoveries += 1;
          onError(
            `Falha de rede, tentando novamente (${networkRecoveries}/${MAX_NETWORK_RECOVERIES})…`,
            'loading',
          );
          hls.startLoad();
          return;
        }
        onError('Erro de rede. Verifique sua conexão e tente novamente.', 'error');
        destroy();
        return;

      case Hls.ErrorTypes.MEDIA_ERROR:
        if (mediaRecoveries < MAX_MEDIA_RECOVERIES) {
          mediaRecoveries += 1;
          onError(
            `Erro de mídia, recuperando (${mediaRecoveries}/${MAX_MEDIA_RECOVERIES})…`,
            'loading',
          );
          // The second attempt swaps the audio codec first — the standard hls.js ladder
          // for a stream whose declared codecs disagree with its segments.
          if (mediaRecoveries > 1) {
            hls.swapAudioCodec();
          }
          hls.recoverMediaError();
          return;
        }
        onError(
          'Erro de mídia irrecuperável. O vídeo pode estar em um codec que este navegador não suporta.',
          'error',
        );
        destroy();
        return;

      default:
        onError('Erro fatal no streaming.', 'error');
        destroy();
    }
  }

  return {
    /** True when this browser needs hls.js rather than native playback. */
    usesMediaSource: () => Hls.isSupported(),

    /** True when the browser plays HLS natively (Safari, iOS). */
    supportsNativeHls: () => videoElement.canPlayType('application/vnd.apple.mpegurl') !== '',

    load(masterUrl) {
      destroy();
      mediaRecoveries = 0;
      networkRecoveries = 0;

      hls = new Hls({
        debug: false,
        startLevel: -1,
        capLevelToPlayerSize: true,
        maxBufferSize: 30 * 1000 * 1000,
        maxBufferLength: 20,
        enableWorker: true,
        lowLatencyMode: false,
      });

      hls.on(Hls.Events.ERROR, (_event, data) => {
        if (data.fatal) {
          handleFatalError(data);
        }
      });
      hls.on(Hls.Events.MANIFEST_PARSED, () => onManifestParsed());
      hls.on(Hls.Events.LEVEL_SWITCHED, (_event, data) => onLevelSwitched(data.level));

      // attachMedia before loadSource, so the media element is ready when the manifest
      // arrives. The original did the reverse; the diagnostics page had it this way round.
      hls.attachMedia(videoElement);
      hls.loadSource(masterUrl);
    },

    loadNative(masterUrl) {
      destroy();
      videoElement.src = masterUrl;
    },

    levels: () => hls?.levels ?? [],
    currentLevel: () => hls?.currentLevel ?? -1,
    loadLevel: () => hls?.loadLevel ?? -1,

    selectLevel(level) {
      if (!hls) {
        return;
      }
      if (level === -1) {
        hls.currentLevel = -1;
        hls.loadLevel = -1;
        hls.autoLevelEnabled = true;
      } else {
        hls.nextLoadLevel = level;
        hls.currentLevel = level;
        hls.autoLevelEnabled = false;
      }
    },

    destroy,
  };
}
