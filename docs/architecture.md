# Architecture

AZTCast turns a magnet link into an adaptive HLS stream. Three stages, in order,
and they are the top-level packages:

```
magnet URI ──▶ acquisition ──▶ transcoding ──▶ playback ──▶ browser
               (bt library)    (ffmpeg)        (HTTP)
                     └──────── ingestion ────────┘
                          (drives and tracks)
```

## Why package-by-feature

The domain *is* the pipeline, so the stages are the packages. Each has exactly
one reason to change: swap the torrent library, change the encoding ladder,
change how bytes are served.

The previous layout was package-by-layer (`controller/`, `service/`,
`service/impl/`, `model/`) and produced the failure that layering reliably
produces: `MagnetStreamingOrchestrator` imported `controller.request.MagnetUrl`,
so the service layer depended on the web layer. Layer packages offer nowhere to
put "the thing that coordinates a use case" and no boundary a compiler can see.

## Package map

```
com.azt.streaming
├── acquisition/          magnet URI -> a video file on disk
│   ├── domain/           TorrentDownloader (port), TorrentDownloadException
│   └── infrastructure/   BtTorrentDownloader, BtRuntimeConfiguration, VideoFileLocator
├── transcoding/          a media file -> an HLS ladder
│   ├── domain/           MediaTranscoder (port), HlsRendition, TranscodingException
│   └── infrastructure/   FfmpegMediaTranscoder, FfmpegCommandBuilder, ProcessRunner,
│                         MasterPlaylistWriter, FfmpegHealthIndicator
├── playback/             HLS assets -> HTTP
│   ├── web/              PlaybackController
│   ├── domain/           HlsAssetLocator (port), HlsMediaTypes, AssetNotFoundException
│   └── infrastructure/   FileSystemHlsAssetLocator
├── ingestion/            drives the pipeline and records progress
│   ├── web/              IngestionController, LegacyVideoController, dto/
│   ├── domain/           StreamJob, StreamJobStatus, StreamJobRepository (port)
│   └── application/      IngestionService, InMemoryStreamJobRepository
└── shared/
    ├── config/           StreamingProperties, Async/Cors/Clock configuration
    ├── storage/          MediaStorage (port), FileSystemMediaStorage
    └── error/            GlobalExceptionHandler, ProblemTypes
```

## Where the ports are, and why only there

Interfaces exist at the two **process boundaries** — a BitTorrent swarm and the
ffmpeg binary — because that is where they pay for themselves. Neither can run
in a unit test, so `TorrentDownloader` and `MediaTranscoder` are what make
`IngestionService` testable in milliseconds.

Every other interface in the previous code existed for no reason and was
deleted. A one-implementation interface with no test double and no seam is a
liability, not abstraction.

## `shared/storage` is the only place that builds a path

Both `transcoding` (writes) and `playback` (reads) go through `MediaStorage`.
Two places constructing filesystem paths from request data are two places that
can get containment wrong; there is one. It normalises and then proves the
result still sits under the media root, and it is where the directory creation
that used to silently fail now lives.

## `shared/error` is a sink, not shared infrastructure

One `@RestControllerAdvice` necessarily names every slice's exceptions, so
`shared.error` depends on all of them. That is safe only because nothing depends
on *it*, which `ArchitectureTest` asserts explicitly. The package cycle rule is
scoped to the four pipeline slices for the same reason.

## Fitness functions

`ArchitectureTest` holds eleven ArchUnit rules. Each one encodes a defect this
codebase actually had, not a style preference — the layer inversion, the
`java.awt.Image` field on a headless server, the `System.out.println` calls, the
C#-style `I`-prefixed interfaces. They run in `mvn verify`, so CI fails on
architectural erosion rather than a reviewer having to notice it.

## Concurrency

`IngestionService` chains acquisition into transcoding with `thenCompose` and
records the outcome in `whenComplete`. Transcoding runs on a named executor
(`transcodingExecutor`) that `@Async` qualifies explicitly;
`AsyncTranscodingIntegrationTest` guards both the qualifier and `@EnableAsync`,
because losing either fails silently.

The pool's `max-size` is how many ffmpeg processes can run at once. ffmpeg's
memory is outside the JVM heap, so raise it and the container memory limit
together.

## Deployment

nginx serves the player and reverse-proxies `/api` onto the same origin, so the
browser never makes a cross-origin request. That topology — not the CORS
configuration — is what allows the API to ship with an empty origin allowlist.

## Known gaps

- **No authentication.** Anyone who can reach the API can make the server
  download arbitrary torrents.
- **Job state is in memory** and lost on restart.
- **No HTTP Range support** on segment responses.
- Transcoding is sequential per video: each rung of the ladder runs after the
  previous one, on a single pool thread.
