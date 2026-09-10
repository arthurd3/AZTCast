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
export function createHlsPlayer(
  videoElement,
  { onManifestParsed, onLevelSwitched, onSubtitlesChanged, onError },
) {
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
      // The tracks arrive with the manifest, but the id of the selected one is only settled
      // once hls.js has applied its own default — so both events feed the same callback.
      hls.on(Hls.Events.SUBTITLE_TRACKS_UPDATED, () => onSubtitlesChanged?.());
      hls.on(Hls.Events.SUBTITLE_TRACK_SWITCH, () => onSubtitlesChanged?.());

      // attachMedia before loadSource, so the media element is ready when the manifest
      // arrives. The original did the reverse; the diagnostics page had it this way round.
      hls.attachMedia(videoElement);
      hls.loadSource(masterUrl);
    },

    loadNative(masterUrl) {
      destroy();
      videoElement.src = masterUrl;
      // Safari exposes the same renditions as TextTracks, and populates them asynchronously
      // after the manifest loads. Without this the picker is empty on exactly one browser.
      videoElement.textTracks?.addEventListener?.('addtrack', () => onSubtitlesChanged?.());
    },

    /**
     * The subtitle renditions, as `{ id, label, language }`.
     *
     * Normalised across the two playback paths: hls.js keeps its own list, and Safari's native
     * HLS surfaces the same EXT-X-MEDIA entries as `video.textTracks`. Both are ordered as the
     * master playlist wrote them, so an id is an index into that order either way.
     */
    subtitleTracks() {
      if (hls) {
        return hls.subtitleTracks.map((track, index) => ({
          id: index,
          label: track.name || track.lang || `Faixa ${index + 1}`,
          language: track.lang ?? '',
        }));
      }
      return [...(videoElement.textTracks ?? [])]
        .map((track, index) => ({ track, index }))
        .filter(({ track }) => track.kind === 'subtitles' || track.kind === 'captions')
        .map(({ track, index }) => ({
          id: index,
          label: track.label || track.language || `Faixa ${index + 1}`,
          language: track.language ?? '',
        }));
    },

    /** -1 means off, which is the default and stays the default. */
    currentSubtitleTrack() {
      if (hls) {
        return hls.subtitleTrack;
      }
      const tracks = [...(videoElement.textTracks ?? [])];
      return tracks.findIndex((track) => track.mode === 'showing');
    },

    selectSubtitleTrack(id) {
      if (hls) {
        hls.subtitleTrack = id;
        // hls.js will not fetch a track's cues while its display flag is off, so a viewer who
        // turns subtitles on gets an empty track until the next fragment boundary without this.
        hls.subtitleDisplay = id !== -1;
        return;
      }
      [...(videoElement.textTracks ?? [])].forEach((track, index) => {
        track.mode = index === id ? 'showing' : 'disabled';
      });
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
