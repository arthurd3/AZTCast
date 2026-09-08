# Package by feature, with ports only at process boundaries

## Status

Accepted

## Context

The backend was organised by layer: `controller/`, `service/`, `service/impl/`,
`model/`. That produced the failure layering reliably produces —
`MagnetStreamingOrchestrator` imported `controller.request.MagnetUrl`, so the
service layer depended on the web layer. Layer packages give you nowhere to put
"the thing that coordinates a use case" and no boundary a compiler can see.

The domain is a three-stage pipeline: magnet → file, file → HLS, HLS → HTTP.

## Decision

One package per pipeline stage — `acquisition`, `transcoding`, `playback` — plus
`ingestion` to drive them and `shared` for the kernel. Each stage has exactly one
reason to change.

Interfaces exist only where a slice crosses a **process boundary**: the
BitTorrent swarm and the ffmpeg binary. Those two cannot run in a unit test, so
`TorrentDownloader` and `MediaTranscoder` are the seams that make the use case
testable. Every other interface was deleted.

`ArchitectureTest` enforces the boundaries with ArchUnit, so erosion fails the
build instead of relying on review.

Rejected: full hexagonal architecture with per-slice domain/application/
infrastructure triads and mapping at every edge. There is no domain model to
protect — the three "model" classes were deleted precisely because they were
anemic and unreferenced. Hexagonal without a domain is ceremony.

Rejected: cleaned-up layering. It is what produced every structural defect found.

## Consequences

- Related code sits together; adding a stage means adding a package.
- Two extra interfaces to maintain, in exchange for fast tests.
- `shared.error` must be exempt from the cycle rule, since one
  `@RestControllerAdvice` names every slice's exceptions. A companion rule
  asserts nothing depends on it, which is what makes the exemption safe.
