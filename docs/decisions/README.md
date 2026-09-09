# Architecture decision records

[MADR](https://adr.github.io/madr/) format. Numbered, immutable once accepted:
to change a decision, add a new record that supersedes the old one rather than
editing it.

| #                                                          | Decision                                        | Status   |
| ---------------------------------------------------------- | ----------------------------------------------- | -------- |
| [0001](0001-monorepo-layout.md)                             | Two applications in one repository              | Superseded in part by [0010](0010-the-player-drives-ingestion.md) |
| [0002](0002-package-by-feature.md)                          | Package by feature, ports at process boundaries | Accepted |
| [0003](0003-filesystem-as-the-store.md)                     | The filesystem is the store; job state is memory| Superseded in part by [0009](0009-redis-for-state-not-for-media.md) |
| [0004](0004-vite-for-the-web-player.md)                     | Build the player with Vite                      | Accepted |
| [0005](0005-ffmpeg-as-an-out-of-process-port.md)            | ffmpeg stays an out-of-process port             | Accepted |
| [0006](0006-deprecate-the-legacy-download-endpoint.md)      | Deprecate rather than change the legacy endpoint| Accepted |
| [0007](0007-nginx-serves-the-bytes.md)                      | nginx serves the bytes; the API authorises      | Accepted |
| [0008](0008-cmaf-ladder-in-one-pass.md)                     | A CMAF ladder, keyframe-aligned, in one pass    | Accepted |
| [0009](0009-redis-for-state-not-for-media.md)               | Redis for state and coordination, not media     | Accepted |
| [0010](0010-the-player-drives-ingestion.md)                 | The player drives ingestion                     | Superseded in part by [0011](0011-the-library-replaces-manual-id-entry.md) |
| [0011](0011-the-library-replaces-manual-id-entry.md)        | The library replaces manual videoId entry       | Extended by [0013](0013-an-ingestion-survives-the-page-that-started-it.md) |
| [0012](0012-custom-player-controls.md)                      | Custom player controls replace the native ones  | Accepted |
| [0013](0013-an-ingestion-survives-the-page-that-started-it.md) | An ingestion survives the page that started it | Accepted |
| [0014](0014-a-provider-log-in-sqlite.md)                    | A provider log, in SQLite                       | Accepted |
| [0015](0015-ip-masking-belongs-to-the-network.md)           | IP masking belongs to the network               | Accepted |
| [0016](0016-the-provenance-map-is-drawn-offline.md)         | The provenance map is drawn offline             | Refined by [0017](0017-an-equirectangular-map-and-derived-signals.md) |
| [0017](0017-an-equirectangular-map-and-derived-signals.md)  | An equirectangular map, and derived signals     | Accepted |
