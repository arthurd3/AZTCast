/**
 * Number and time formatting shared across pages.
 *
 * A leaf module on purpose: it started out inside worldMap.js, which meant the library page
 * imported Leaflet — 144 kB of mapping library — to print "1,4 GB" under a progress bar. Anything
 * two pages need lives somewhere neither of them has to pay for.
 */

/** Bytes as `1,4 GB`, in SI units because that is what a network reports. */
export function formatBytes(bytes) {
  if (!bytes) {
    return '0 B';
  }
  const units = ['B', 'kB', 'MB', 'GB', 'TB'];
  const exponent = Math.min(units.length - 1, Math.floor(Math.log10(bytes) / 3));
  const value = bytes / 1000 ** exponent;
  const digits = value < 10 && exponent > 0 ? 1 : 0;
  return `${value.toLocaleString('pt-BR', { maximumFractionDigits: digits })} ${units[exponent]}`;
}

/** How long ago, coarsely. The exact second something was last seen is never the question. */
export function relativeTime(isoTimestamp) {
  const seconds = Math.max(0, Math.round((Date.now() - Date.parse(isoTimestamp)) / 1000));
  if (seconds < 60) {
    return `${seconds}s`;
  }
  if (seconds < 3600) {
    return `${Math.floor(seconds / 60)}m`;
  }
  if (seconds < 86400) {
    return `${Math.floor(seconds / 3600)}h`;
  }
  return `${Math.floor(seconds / 86400)}d`;
}
