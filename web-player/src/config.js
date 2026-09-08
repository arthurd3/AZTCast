/**
 * Base URL for the streaming API.
 *
 * Empty by default, which makes every request relative to the page's own origin: Vite
 * proxies /api in development and nginx does the same in production, so the browser
 * never makes a cross-origin request and the API needs no CORS allowlist.
 *
 * This replaces a hardcoded `http://localhost:8080` that appeared in two files, could
 * not be deployed anywhere, and would have been blocked as mixed content over HTTPS.
 */
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '';
