/**
 * Base URL for the streaming API.
 *
 * Empty by default, which makes every request relative to the page's own origin: Vite
 * proxies /api in development and nginx does the same in production, so the browser
 * never makes a cross-origin request and the API needs no CORS allowlist.
 *
 * This replaces an absolute backend origin that was hardcoded in two files: the app
 * could not be deployed anywhere else, and it would have been blocked as mixed
 * content when served over HTTPS. CI greps for that literal to keep it from coming
 * back, so do not write it here either.
 */
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '';
