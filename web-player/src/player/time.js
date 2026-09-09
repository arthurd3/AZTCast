/**
 * Clock formatting, shared by the seek bar, the time readout and the resume prompt.
 */

/** `4:07` under an hour, `1:04:07` over it — the shape every player uses. */
export function clock(seconds) {
  if (!Number.isFinite(seconds) || seconds < 0) {
    return '0:00';
  }
  const total = Math.floor(seconds);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const rest = total % 60;

  const pad = (value) => String(value).padStart(2, '0');
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(rest)}` : `${minutes}:${pad(rest)}`;
}

/**
 * The same instant in words, for aria-valuetext.
 *
 * A screen reader reading "4:07" says "four oh seven", which is a time of day. Sliders are
 * required to expose a human-readable value and this is what that means here.
 */
export function spoken(seconds) {
  if (!Number.isFinite(seconds) || seconds < 0) {
    return '0 segundos';
  }
  const total = Math.floor(seconds);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const rest = total % 60;

  const parts = [];
  if (hours > 0) {
    parts.push(`${hours} ${hours === 1 ? 'hora' : 'horas'}`);
  }
  if (minutes > 0) {
    parts.push(`${minutes} ${minutes === 1 ? 'minuto' : 'minutos'}`);
  }
  // "0 segundos" beats saying nothing at all at the very start of a video.
  if (rest > 0 || parts.length === 0) {
    parts.push(`${rest} ${rest === 1 ? 'segundo' : 'segundos'}`);
  }
  return parts.join(' e ');
}
