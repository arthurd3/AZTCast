# The swarm runs in a process of its own

## Status

Accepted

Supersedes [ADR-0023](0023-the-bittorrent-runtime-outlives-its-clients.md), whose subject — a
shared `BtRuntime` outliving the clients attached to it — no longer exists in this process. The
reasoning survives the move: the engine keeps one session shared by every download, for the reasons
that record gives. Refines [ADR-0031](0031-only-the-swarm-faces-outward.md), which closed the HTTP
surface and named the swarm port as the one thing that has to stay open; this is what is now on the
far side of it. It also settles a question [ADR-0015](0015-ip-masking-belongs-to-the-network.md)
left open, and which `deploy/docker-compose.vpn.yml` stated in prose as the thing it could not do.

## Context

The BitTorrent client ran inside the API's JVM. Every byte from an unknown peer was parsed in the
process that also holds the media root open for writing, the job store, the SQLite provider
database and whatever Redis connection was live at the time. A memory-safety or logic bug in the
peer-wire code was a bug in all of it.

The library doing that parsing is `bt` 1.10, which ADR-0015 records as the final release of an
abandoned project. It is not going to be patched. That is an uncomfortable thing to have on the
internet-facing side of a service whose whole remaining exposure, after ADR-0031, is the swarm port.

There was a second cost, and the VPN overlay named it. Confining acquisition to a tunnel meant
putting the entire API into gluetun's network namespace, because that was the only container the
swarm lived in. The overlay said so plainly: *"If you would rather the API kept serving finished
video while only acquisition is confined, acquisition has to move into a container of its own — a
bigger change than this file."* Until acquisition was its own process, a dropped VPN took the
library down with it.

## Decision

Acquisition moves out of the JVM into a separate, sandboxed process, behind the port that already
existed for it.

- **`TorrentDownloader` was already the seam.** Its own javadoc says so — *"This is a port, not an
  incidental interface. It wraps a real BitTorrent swarm"* — so the out-of-process engine is a
  second implementation of an existing interface rather than a restructuring. `PeerObservationSink`
  and `SwarmSelfView` are the other two, pointing the other way, and both are satisfied over the
  same connection.

- **libtorrent-rasterbar 2.0.11, in C++.** Maintained and standard where `bt` 1.10 is neither. The
  language is a consequence of the library, not a preference: C++ parsing hostile network traffic is
  exactly where memory-safety bugs land, which is why the sandbox below is a requirement of this
  decision and not an accompaniment to it. An engine written from scratch should have been Rust;
  there is no Rust BitTorrent library within reach of libtorrent's maturity, and trading an
  abandoned library for an immature one is not an improvement.

- **A Unix socket, one connection per download.** Not a TCP port on loopback: there is no address
  for anything on this machine or any network to dial, only a path with a mode on it, so who may
  drive the engine is a filesystem question with a filesystem answer. It also means the engine
  inherits none of the browser-facing problem ADR-0031 had to spend a filter on. The connection *is*
  the download — no request ids, no multiplexing, and closing the socket is how a download is
  cancelled.

- **Newline-delimited JSON.** Not for speed, which it does not have, but because both ends already
  have a parser for it and because a protocol you can read with `nc` while it is failing is worth
  more than the microseconds. Nothing on this socket is hot: a few hundred messages over hours.

- **The sandbox is the load-bearing part.** Its own user, all capabilities dropped, a read-only
  root filesystem, its own Docker network, and a subpath mount giving it `downloads/` and not the
  HLS root. Confirmed rather than asserted: from inside the running container, `CapPrm`, `CapEff`
  and `CapBnd` are all zero, `/var/lib/aztcast` contains `downloads` and no `hls`, the root
  filesystem refuses a write, and `redis`, `streaming-api` and `web-player` do not resolve while
  external names still do.

- **Off by default, on in the compose stack.** `aztcast.streaming.torrent.engine` selects it, and
  the two engines are mutually exclusive by condition. That exclusivity is not tidiness: left
  unconditional, choosing the brokered engine would still have started a `BtRuntime` in the JVM,
  opened the peer port there and bootstrapped DHT there — a real sandbox guarding an empty room.
  The default stays embedded because it needs nothing installed; the `docker` profile brokers,
  because a container is where a sandbox can actually be applied.

## Consequences

- **The VPN overlay is now the smaller thing it wanted to be.** Only the engine goes into gluetun's
  namespace, so a dropped tunnel stops downloads and leaves the library, the player and every
  finished video serving. `FIREWALL_OUTBOUND_SUBNETS` is gone with it: the hole existed so the API
  could reach Redis and nginx could reach the API from inside the tunnel, and the only thing in
  there now talks to the rest of the stack through a file.

- **There are two BitTorrent engines to keep in step, and that is a real cost.** Both must agree on
  progress semantics, file selection and five kinds of peer observation. Tracker injection is shared
  code for exactly this reason — `MagnetTrackerInjector` renders the augmented magnet to a string
  rather than letting the engine merge its own list — but the rest is parity by test and by
  inspection. The embedded engine should eventually go; it stays for now because it is what runs
  without a container.

- **`DISCOVERED` means something slightly different.** The embedded engine reports it when a
  tracker, DHT or PEX names a peer. libtorrent does not expose that individually, so the brokered
  engine reports it when it decides to dial a peer — the closest honest analogue, since the peer was
  named by some source and is being acted on. `CONNECTED` then comes from the poller once the peer
  is actually in the live list. The provenance page counts are comparable; they are not identical.

- **Only the first download's network settings take effect.** The session is shared, so the second
  magnet cannot reconfigure the listen port out from under the first. Settings arrive over the wire
  rather than as engine flags so that `application.yml` stays the one place the swarm is configured;
  the cost is that changing them needs the engine restarted, and the engine says so in its log each
  time it ignores a set.

- **A path from the engine is a claim, not a fact.** It is the least trusted process here by
  construction, and the file it names is one the transcoder will open, so `BrokeredTorrentDownloader`
  re-checks that the path is inside the directory the engine was given.

- **Two bugs found by running it, both invisible to a compiler.** `alert_cast` matches a concrete
  alert type, so casting to the base `torrent_alert` silently dropped every torrent alert there is —
  metadata, finished, error and both peer edges — and the download still worked, because progress
  and most peer facts come from polling. And `peer_connect_alert` lives in `alert_category::connect`
  rather than `peer`, so a plausible-looking alert mask produced a provider log missing two of its
  five event kinds. Neither would have been caught by anything short of a real swarm.

- **The repo now builds C++.** A monorepo that was Java and JavaScript has a third toolchain, a
  CMake build and a Debian-only dependency. The engine builds in its container rather than on a
  contributor's laptop, which keeps `make test` unchanged for anyone not touching it — and means
  the engine's own build is not covered by that command.
