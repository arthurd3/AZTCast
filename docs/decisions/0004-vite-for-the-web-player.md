# Build the player with Vite

## Status

Accepted

## Context

The player was three static files with no build step. That is the right call for
a page with no dependencies — but this page had one, loaded as:

```html
<script src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
```

Unpinned, no SRI, no lockfile. A supply-chain hole, an availability dependency
on a CDN, and a reproducibility hole all at once: the app's behaviour could
change overnight with no commit.

The backend URL was hardcoded as an absolute origin in two files, so the app
could not be deployed anywhere and would have been mixed-content blocked over
HTTPS.

## Decision

Add npm and Vite.

Pinning hls.js requires a package manager; serving it from `node_modules`
requires a bundler. Splitting the 128-line `loadVideo()` requires ES modules,
and modules do not work over `file://`, so a dev server is needed regardless.
Vite is the smallest thing that does all three.

`import.meta.env.VITE_API_BASE_URL` defaults to empty, making every request
relative. Vite proxies `/api` in development, nginx does the same in production.

Rejected: TypeScript. It would catch real classes of bug here, but it is a
second migration on top of a restructure. `allowJs` makes it a later, file-by-file
move.

Rejected: a `<meta>` tag for runtime configuration. It is the right pattern for
one image deployed to many environments, but with nginx proxying `/api` the URL
is always same-origin — indirection to configure a constant empty string.

## Consequences

- Dependencies are pinned and updated through reviewable Dependabot PRs.
- Node is now required to build the player.
- The deployment topology, not the CORS configuration, is what makes requests
  same-origin — which is what lets the API ship with an empty origin allowlist.
