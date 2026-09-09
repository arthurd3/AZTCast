import { defineConfig } from 'vite';
import { resolve } from 'node:path';

const API_TARGET = process.env.VITE_DEV_API_TARGET ?? 'http://localhost:8080';

export default defineConfig({
  server: {
    port: 5173,
    // Same-origin in development. This is what lets the API ship with an empty CORS
    // allowlist: the browser only ever talks to :5173, and Vite forwards /api to the
    // backend. Production does the same thing with nginx.
    proxy: {
      '/api': { target: API_TARGET, changeOrigin: true },
      '/actuator': { target: API_TARGET, changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    // hls.js alone is ~575 kB; the app code is ~3 kB per page. The default 500 kB
    // warning would fire on every build for a dependency we cannot shrink.
    chunkSizeWarningLimit: 700,
    sourcemap: true,
    rollupOptions: {
      input: {
        main: resolve(import.meta.dirname, 'index.html'),
        player: resolve(import.meta.dirname, 'player.html'),
        diagnostics: resolve(import.meta.dirname, 'diagnostics.html'),
        providers: resolve(import.meta.dirname, 'providers.html'),
      },
      output: {
        // hls.js is ~575 kB and both pages use it. Give it its own named chunk so the
        // size is attributed to the library rather than to whichever app module the
        // bundler happened to name the shared chunk after, and so it caches across
        // deploys that only touch app code.
        // Leaflet and the world topology get the same treatment for the same reason: they are
        // ~190 kB that only the provenance page loads, and attributing them to their own chunk
        // keeps that visible in the build output instead of buried in a shared bundle.
        manualChunks: (id) => {
          if (id.includes('node_modules/hls.js')) {
            return 'hls';
          }
          if (id.includes('node_modules/leaflet') || id.includes('node_modules/world-atlas')) {
            return 'atlas';
          }
          return undefined;
        },
      },
    },
  },
});
