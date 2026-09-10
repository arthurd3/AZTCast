/**
 * The starfield behind every page.
 *
 * Canvas rather than hundreds of DOM nodes, and drawn once per frame into a single layer:
 * the nebula itself is CSS (body::before) and costs nothing, so this file only has to move
 * points of light.
 *
 * It is deliberately cheap, because on the watch page it competes with video decoding for the
 * same GPU. Three things keep it that way: it stops when the tab is hidden, it stops when it
 * scrolls out of view, and `pause()` lets the watch page stop it outright while playing.
 */
const LAYERS = [
  { count: 90, radius: [0.4, 0.9], speed: 0.006, alpha: [0.25, 0.55] },
  { count: 45, radius: [0.7, 1.4], speed: 0.014, alpha: [0.4, 0.75] },
  { count: 18, radius: [1.1, 2.1], speed: 0.026, alpha: [0.6, 1] },
];

/** Twinkle period, in milliseconds. Long enough to read as drift, not as flicker. */
const TWINKLE_MS = 4200;

export function createCosmos(canvas) {
  const context = canvas.getContext('2d', { alpha: true });
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');

  let stars = [];
  let frame = null;
  let width = 0;
  let height = 0;
  /** Reasons the animation is currently stopped. Empty means it should be running. */
  const blockers = new Set();

  function resize() {
    // Cap the backing store at 2x. A 4K screen at devicePixelRatio 3 would otherwise ask for a
    // canvas with nine times the pixels for stars that are one pixel wide.
    const scale = Math.min(window.devicePixelRatio || 1, 2);
    width = canvas.clientWidth;
    height = canvas.clientHeight;
    canvas.width = Math.floor(width * scale);
    canvas.height = Math.floor(height * scale);
    context.setTransform(scale, 0, 0, scale, 0, 0);
    seed();
  }

  function seed() {
    stars = [];
    // Scale the count to the area, so a phone does not draw a desktop's worth of stars.
    const density = Math.min((width * height) / (1440 * 900), 1.6);
    for (const layer of LAYERS) {
      const count = Math.round(layer.count * density);
      for (let index = 0; index < count; index += 1) {
        stars.push({
          x: Math.random() * width,
          y: Math.random() * height,
          radius: random(layer.radius),
          speed: layer.speed,
          alpha: random(layer.alpha),
          phase: Math.random() * Math.PI * 2,
        });
      }
    }
  }

  function draw(time) {
    context.clearRect(0, 0, width, height);
    for (const star of stars) {
      // Sine on a per-star phase offset, so the field breathes instead of blinking in unison.
      const twinkle = 0.75 + 0.25 * Math.sin(time / TWINKLE_MS + star.phase);
      context.globalAlpha = star.alpha * twinkle;
      context.beginPath();
      context.arc(star.x, star.y, star.radius, 0, Math.PI * 2);
      context.fill();
    }
    context.globalAlpha = 1;
  }

  function step(time) {
    for (const star of stars) {
      // Upward drift: the viewer is falling through the field, not watching it fall.
      star.y -= star.speed * star.radius * 6;
      if (star.y < -2) {
        star.y = height + 2;
        star.x = Math.random() * width;
      }
    }
    draw(time);
    frame = window.requestAnimationFrame(step);
  }

  function start() {
    if (frame !== null || blockers.size > 0) {
      return;
    }
    if (reduceMotion.matches) {
      // One static frame. It still looks like a sky; it just does not move.
      draw(0);
      return;
    }
    frame = window.requestAnimationFrame(step);
  }

  function stop() {
    if (frame !== null) {
      window.cancelAnimationFrame(frame);
      frame = null;
    }
  }

  function block(reason) {
    blockers.add(reason);
    stop();
  }

  function unblock(reason) {
    blockers.delete(reason);
    start();
  }

  context.fillStyle = '#ffffff';
  resize();

  // ResizeObserver rather than the resize event: it also fires when the canvas changes size
  // without the window doing so, which is what happens when a scrollbar appears.
  const resizeObserver = new ResizeObserver(() => {
    resize();
    if (reduceMotion.matches) {
      draw(0);
    }
  });
  resizeObserver.observe(canvas);

  document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
      block('hidden');
    } else {
      unblock('hidden');
    }
  });

  // Scrolled past: the canvas is fixed and full-viewport, so this only trips when the page
  // itself is off screen, but it costs one observer to never animate an invisible field.
  const visibility = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (entry.isIntersecting) {
        unblock('offscreen');
      } else {
        block('offscreen');
      }
    }
  });
  visibility.observe(canvas);

  reduceMotion.addEventListener('change', () => {
    stop();
    start();
  });

  start();

  return {
    /** Stops the field. The watch page calls this while video is playing. */
    pause: () => block('playback'),
    resume: () => unblock('playback'),
  };
}

/**
 * Inserts the canvas and starts the field. Returns null when the page opted out.
 *
 * Every page calls this the same way, which is why the canvas is created here rather than
 * being markup each page has to remember to include.
 */
export function mountCosmos() {
  const canvas = document.createElement('canvas');
  canvas.className = 'cosmos-canvas';
  canvas.setAttribute('aria-hidden', 'true');
  document.body.prepend(canvas);
  return createCosmos(canvas);
}

function random([min, max]) {
  return min + Math.random() * (max - min);
}
