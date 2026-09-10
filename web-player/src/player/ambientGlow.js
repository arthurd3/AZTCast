/**
 * Ambient light: the glow behind the frame takes its colour from the picture.
 *
 * The whole thing is one 32x18 canvas sampled twice a second. That is 576 pixels per sample,
 * which is why this can run alongside a 1080p decode without being noticed — the expensive
 * version of this effect (a blurred, scaled copy of the video painted behind itself) is what
 * it is deliberately not.
 *
 * Requires the video to be CORS-clean or the canvas is tainted and getImageData throws.
 * `crossorigin="anonymous"` is on the element in player.html, and the segments come from the
 * same origin anyway, so this holds — but it is wrapped, because a tainted canvas must not
 * take playback down with it.
 */
const SAMPLE_MS = 500;
const WIDTH = 32;
const HEIGHT = 18;

/** Below this the frame is essentially black and a glow would just be grey haze. */
const MIN_LUMA = 12;

export function createAmbientGlow(video, target) {
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    // The effect is a slow colour crossfade. With motion off, there is nothing to show.
    return { start() {}, stop() {} };
  }

  const canvas = document.createElement('canvas');
  canvas.width = WIDTH;
  canvas.height = HEIGHT;
  // willReadFrequently: without it browsers keep the canvas on the GPU and every getImageData
  // is a stall while the frame is read back.
  const context = canvas.getContext('2d', { willReadFrequently: true });

  let timer = null;
  let failed = false;

  function sample() {
    if (failed || video.readyState < 2 || video.paused) {
      return;
    }
    try {
      context.drawImage(video, 0, 0, WIDTH, HEIGHT);
      const { data } = context.getImageData(0, 0, WIDTH, HEIGHT);

      let red = 0;
      let green = 0;
      let blue = 0;
      for (let index = 0; index < data.length; index += 4) {
        red += data[index];
        green += data[index + 1];
        blue += data[index + 2];
      }
      const pixels = data.length / 4;
      red = Math.round(red / pixels);
      green = Math.round(green / pixels);
      blue = Math.round(blue / pixels);

      // Letterboxed or dark scenes: fall back to the theme rather than glowing grey.
      const luma = 0.2126 * red + 0.7152 * green + 0.0722 * blue;
      target.style.removeProperty('--ambient');
      if (luma >= MIN_LUMA) {
        target.style.setProperty('--ambient', `rgb(${red} ${green} ${blue})`);
      }
    } catch {
      // Tainted canvas. Stop trying; the static glow underneath is a fine result.
      failed = true;
      stop();
    }
  }

  function start() {
    if (timer === null && !failed) {
      timer = window.setInterval(sample, SAMPLE_MS);
    }
  }

  function stop() {
    if (timer !== null) {
      window.clearInterval(timer);
      timer = null;
    }
  }

  video.addEventListener('play', start);
  video.addEventListener('pause', stop);
  video.addEventListener('emptied', () => target.style.removeProperty('--ambient'));

  return { start, stop };
}
