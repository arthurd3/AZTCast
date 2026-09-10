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
| [0012](0012-custom-player-controls.md)                      | Custom player controls replace the native ones  | Superseded in part by [0022](0022-subtitles-become-webvtt-sidecars.md); amended by [0028](0028-scrollbars-are-themed-and-tables-do-not-scroll-sideways.md) |
| [0013](0013-an-ingestion-survives-the-page-that-started-it.md) | An ingestion survives the page that started it | Amended by [0024](0024-transcode-progress-is-its-own-figure.md) |
| [0014](0014-a-provider-log-in-sqlite.md)                    | A provider log, in SQLite                       | Accepted |
| [0015](0015-ip-masking-belongs-to-the-network.md)           | IP masking belongs to the network               | Refined by [0031](0031-only-the-swarm-faces-outward.md), [0032](0032-the-swarm-runs-in-a-process-of-its-own.md) |
| [0016](0016-the-provenance-map-is-drawn-offline.md)         | The provenance map is drawn offline             | Refined by [0017](0017-an-equirectangular-map-and-derived-signals.md); amended by [0029](0029-the-address-is-masked-until-asked-for.md) |
| [0017](0017-an-equirectangular-map-and-derived-signals.md)  | An equirectangular map, and derived signals     | Accepted |
| [0018](0018-the-top-rung-is-copied-not-encoded.md)          | The top rung is copied, not encoded             | Supersedes part of [0008](0008-cmaf-ladder-in-one-pass.md); superseded in part by [0021](0021-one-audio-rendition-shared-by-every-rung.md) |
| [0019](0019-kept-videos-outlive-the-retention-window.md)    | Kept videos outlive the retention window        | Amends [0003](0003-filesystem-as-the-store.md), [0009](0009-redis-for-state-not-for-media.md); superseded in part by [0030](0030-the-library-is-not-a-cache.md) |
| [0020](0020-ffmpeg-capabilities-are-probed-not-assumed.md)   | ffmpeg capabilities are probed, not assumed      | Supersedes part of [0005](0005-ffmpeg-as-an-out-of-process-port.md) |
| [0021](0021-one-audio-rendition-shared-by-every-rung.md)    | One audio rendition, shared by every rung        | Supersedes part of [0018](0018-the-top-rung-is-copied-not-encoded.md); superseded in part by [0025](0025-a-ladder-the-browser-accepts.md), [0027](0027-audio-keeps-the-channels-the-source-had.md) |
| [0022](0022-subtitles-become-webvtt-sidecars.md)            | Subtitles become WebVTT sidecars                 | Supersedes part of [0012](0012-custom-player-controls.md) |
| [0023](0023-the-bittorrent-runtime-outlives-its-clients.md) | The BitTorrent runtime outlives its clients      | Superseded by [0032](0032-the-swarm-runs-in-a-process-of-its-own.md) |
| [0024](0024-transcode-progress-is-its-own-figure.md)        | Transcode progress is its own figure             | Amends [0013](0013-an-ingestion-survives-the-page-that-started-it.md) |
| [0025](0025-a-ladder-the-browser-accepts.md)                 | A ladder the browser accepts                     | Supersedes part of [0021](0021-one-audio-rendition-shared-by-every-rung.md) |
| [0026](0026-verified-before-it-is-published-repairable-after.md) | Verified before it is published, repairable after | Amends [0003](0003-filesystem-as-the-store.md) |
| [0027](0027-audio-keeps-the-channels-the-source-had.md)      | Audio keeps the channels the source had          | Supersedes part of [0021](0021-one-audio-rendition-shared-by-every-rung.md) |
| [0028](0028-scrollbars-are-themed-and-tables-do-not-scroll-sideways.md) | Scrollbars are themed, and tables do not scroll sideways | Amends [0012](0012-custom-player-controls.md) |
| [0029](0029-the-address-is-masked-until-asked-for.md) | The address is masked until it is asked for | Amends [0016](0016-the-provenance-map-is-drawn-offline.md) |
| [0030](0030-the-library-is-not-a-cache.md) | The library is not a cache | Supersedes part of [0019](0019-kept-videos-outlive-the-retention-window.md); amends [0003](0003-filesystem-as-the-store.md), [0009](0009-redis-for-state-not-for-media.md) |
| [0031](0031-only-the-swarm-faces-outward.md) | Only the swarm faces outward | Refines [0015](0015-ip-masking-belongs-to-the-network.md); refined by [0032](0032-the-swarm-runs-in-a-process-of-its-own.md) |
| [0032](0032-the-swarm-runs-in-a-process-of-its-own.md) | The swarm runs in a process of its own | Supersedes [0023](0023-the-bittorrent-runtime-outlives-its-clients.md); refines [0015](0015-ip-masking-belongs-to-the-network.md), [0031](0031-only-the-swarm-faces-outward.md) |
