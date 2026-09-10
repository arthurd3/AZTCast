# The BitTorrent runtime outlives its clients

## Status

Accepted

## Context

Every finished download ended the same way: a page of stack traces.

```
ERROR bt.peer.PeerRegistry  : Error when querying peer source: TrackerPeerSource {…}
java.util.concurrent.RejectedExecutionException: Task … rejected from
  java.util.concurrent.ThreadPoolExecutor@…[Terminated, pool size = 0, …]
ERROR bt.net.IncomingConnectionListener : Unexpected error
java.lang.RuntimeException: Incoming channel @ /172.20.0.1:6891 has been closed, …
```

It read as shutdown noise. It was not.

`BtRuntimeConfiguration` exists precisely so that one runtime is shared: its own javadoc explains
that `Bt.client()` builds a private runtime per download, and that doing so stands up an entire
independent BitTorrent stack per ingestion — its own selector threads, its own DHT node, and its
own losing attempt to bind the acceptor port.

But `BtClient.stop()` calls `BtRuntime.detachClient`, and that method reads:

```java
if (knownClients.remove(client)) {
    if (!manualShutdownOnly && knownClients.isEmpty()) {
        shutdown();
    }
}
```

`manualShutdownOnly` is set only by `BtRuntimeBuilder.disableAutomaticShutdown()`, which was never
called. So the first download to finish tore down the shared runtime: the DHT node, the peer
registry and the connection acceptor that every *later* download depends on.

The exceptions are that teardown racing its own in-flight scheduled tasks. The real damage is what
happens next. `DefaultClient` sees a stopped runtime and calls `startup()` on it, which re-runs the
startup lifecycle hooks against Guice singletons whose executors were already `shutdownNow()`n.
Both `shutdown()` and `startup()` flip the same `AtomicBoolean` by CAS, so nothing throws. The
second ingestion in a process simply does not work properly, and says nothing about it.

The same log line carried a second, unrelated problem: `bind=/172.20.0.1`.

`Config`'s default acceptor address is `NetworkUtil.getInetAddressFromNetworkInterfaces()`, which
takes the first non-loopback IPv4 address the JVM enumerates — no `isUp()` check, no route lookup,
no scoring. On this machine the JVM enumerates sixteen Docker bridges before the physical NIC, so
every run bound the listening socket to a container bridge. It still downloads, because outbound
connections are NATed back onto the real interface, but no peer on the internet can reach the
listening socket. The client is inbound-dead in every swarm it joins.
[ADR-0015](0015-ip-masking-belongs-to-the-network.md) already names this as "not a considered
choice".

## Decision

**Call `disableAutomaticShutdown()` on the builder.** Spring already owns the lifecycle through
`@Bean(destroyMethod = "shutdown")`; this stops the library from also owning it, badly.

**Resolve the bind address instead of inheriting a scan.** In order: whatever
`acceptor-address` names, then the source address of this host's default route, then the first
interface that is up and does not look like a container bridge, then the library's own behaviour.

The default route is found by connecting a UDP socket to TEST-NET-3 (`203.0.113.1`, reserved for
documentation) on the discard port. Connecting a datagram socket sends no packet; it asks the
kernel to resolve a route and bind a source address, and then the socket reports which one it
picked. The chosen address, the interface carrying it and the reason are logged at startup, and
picking something that looks virtual is a warning rather than a silent fact.

**Bound the announce list at both ends.** `MagnetTrackerInjector` already merged configured
trackers into a magnet; it now also strips a configured list of dead ones, matched on host so one
entry covers every spelling. And `Config.setTrackerTimeout` — which the library leaves unset — is
configurable, defaulting to 8s.

## Consequences

- **Zero `RejectedExecutionException` after a download**, measured across a full ingestion of the
  reference magnet. Before: roughly ten, every time.

- **`bind=/192.168.2.112 on enp4s0 — the source address of this host's default route`**, where
  the same machine used to say `bind=/172.20.0.1`. Inbound peer connections can reach the client.

- **The second ingestion in a process works.** This is the change's real point and the hardest part
  of it to test: without starting the runtime there is no observable difference, and starting it
  binds a real port and a real DHT node. It is covered by the one-line change being explained where
  it sits, and verified by running two ingestions.

- **Dead trackers are not announced to.** The reference magnet carried seven that have been down
  for years — `public.popcorn-tracker.org`, `tracker.coppersurfer.tk`, `torrent.gresille.org`,
  `tracker.internetwarriors.net`, `glotorrents.pw`, `p4p.arenabg.com`, `tracker.bittor.pw` — and
  the library queries peer sources serially, so each one delayed the live trackers behind it. None
  of them appears in a timeout after this change.

- **The progress log stopped repeating the magnet.** Every tick carried the full URI, around 1.5 kB
  of tracker query string, fifteen times per download. It is the reason the failure that started
  all of this arrived buried in screenfuls of the same repeated string. The display name is logged
  instead; the URI is one DEBUG line away.
