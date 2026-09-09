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
| [0010](0010-the-player-drives-ingestion.md)                 | The player drives ingestion                     | Accepted |
