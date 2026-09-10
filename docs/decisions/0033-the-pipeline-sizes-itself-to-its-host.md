# The pipeline sizes itself to its host

## Status

Accepted

Supersedes the sizing half of [ADR-0008](0008-cmaf-ladder-in-one-pass.md), which established the
one-pass ladder and left every encoder in it to size its own thread pool. The ladder shape is
unchanged; what changes is that the encoders in it now know how many of them there are. Leaves
[ADR-0020](0020-ffmpeg-capabilities-are-probed-not-assumed.md)'s hardware finding where it is —
still detected, still unused — and records why that is now the largest thing left on the table.

## Context

Nothing passed `-threads`, so every `libx264` instance sized its own pool from the whole machine,
independently, knowing nothing about the three or four other instances in the same process. On a
32-thread workstation that is harmless. Everywhere else it is the dominant cost.

The measurement that prompted this was looking for something else. The premise was that a four-rung
ladder oversubscribing a 32-thread box was costing throughput; it was not. Uncapped beat every
capped configuration on that host, and still beat them with two videos encoding at once — 9.6s
against 9.8s. The premise was wrong and the numbers said so before any code changed.

The real defect only appears on hardware that is not a workstation, and the reason is a mismatch
between two ideas of "how many CPUs are there":

- `Runtime.availableProcessors()` reads the cgroup quota. A `--cpus=2` container reports 2.
- x264 reads `sched_getaffinity`, which `--cpus` does not touch. The same container reports 32.

So a two-core container ran a ladder's worth of threads sized for the host underneath it and was
then CFS-throttled onto two cores of runtime, spending more time switching between threads than
encoding. That is not a tuning opportunity; it is the default configuration being wrong by a factor
of two on every machine smaller than the one it was written on.

## Decision

The ladder's thread budget is derived from the host, once per plan.

- **x264's heuristic is kept; its input is corrected.** Left alone it applies `1.5 × cores` to the
  whole machine, once per rung. It now applies to the share of the machine a rung actually gets:
  `1.5 × (cores / encoded rungs)`, clamped to `[2, 16]`.

- **Not also divided by the concurrency ceiling,** and this was measured and rejected rather than
  assumed. Dividing by both starves the common case — one video encoding alone — to protect a case
  that does not need protecting, since two ladders at once on a large host cost nothing extra. It
  measured 13% slower on the workstation for no gain anywhere.

- **The floor is 2 and the ceiling is 16.** One thread per rung loses more to serialisation than it
  saves; past sixteen a frame-threaded encoder buys latency and buffers rather than speed.

- **x264's height cap is re-applied by hand,** because an explicit `-threads` bypasses it. x264 caps
  its own count at half the picture's macroblock rows on the auto path only, so a 240p rung handed
  twelve frame threads would get twelve frames of latency and twelve frame buffers to divide fifteen
  rows of macroblocks between.

- **`-filter_complex_threads 1`,** and this one is not host-derived. The graph is a serial cascade —
  each rung scales from the one above it — and ffmpeg already runs the whole graph on its own thread
  with every encoder on theirs. Slice-threading each `scale` across every core pays a barrier per
  filter per frame for sub-microsecond work: 29% more CPU for 3% less speed.

- **`encoder-threads: auto`,** the sentinel this codebase already uses for `video-codec`, `preset`
  and `channels`. A number pins it; `0` restores ffmpeg's own behaviour.

Measured, same source, same rungs, alternating runs, best of two:

```
  cores   default   sized     gain
     2     39.0s     22.0s    1.77x
     4     20.3s     10.8s    1.88x
    32      4.3s      4.3s      —
```

## Consequences

- **The workstation case is deliberately a draw.** There was no throughput being lost there, so
  there was none to recover. The value of this record is that the same code is no longer badly wrong
  on a laptop or in a container — which is the property that was actually asked for.

- **Peak RSS falls with the thread count**, roughly 750 MB to 500 MB for one ladder on the reference
  box. That is not the reason for the change but it is the reason it interacts with `memory: 3g` in
  compose and the exit-137 story in the runbook.

- **`nice` was tried and rejected.** The obvious answer to "the machine feels overloaded" is to
  lower ffmpeg's priority, and it measured as a placebo: a competing CPU task ran in 0.55s during a
  transcode either way, against 0.36s idle. Shipping it would have been a comforting no-op.

- **The largest remaining win is hardware encoding, and it is still not taken.** ADR-0020 left
  `h264_vaapi` detected and unused; it still is. What the measurements here add is the shape of the
  prize: a full-GPU ladder costs about 0.17 of a core against roughly 20 of them, and running one
  GPU ladder and one software ladder *concurrently* measured 0.155 videos/sec against 0.106 for the
  best software-only arrangement — **+46%**, because the two use almost disjoint resources. GPU
  concurrency beyond one is worthless (the encode engine is one serialised resource; eight at once
  measured exactly eight times the latency and no more throughput), so the shape is one GPU lane
  beside the CPU lanes rather than a bigger pool.

  It is not taken here because it changes output quality and this record is about spending the same
  quality faster. `h264_vaapi` on this driver is worse per bit than `libx264 -preset veryfast` at
  these rung bitrates, and two videos ingested at once would land in different lanes and come out
  visibly different from each other. That needs a VMAF number and a decision about consistency, not
  a thread count.

- **Two things measured here are properties of one machine.** The 1.5 factor and the clamps fit
  every host tried — 2, 4, 8 and 32 cores — but all of them were this CPU, and all the timings come
  from one 1080p24 H.264 source. The shapes were stable across repetitions; the absolute figures are
  not portable claims.
