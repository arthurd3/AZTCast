# IP masking belongs to the network, not to the application

## Status

Accepted

## Context

The ask was to hide this machine's address when downloading. The first instinct — a proxy
setting in the BitTorrent client — does not work here, and it is worth writing down why so
nobody spends a day rediscovering it.

**The library cannot proxy, and cannot be made to.** `bt` 1.10 has no SOCKS or HTTP proxy
support anywhere in it. Outgoing peer connections are raw NIO `SocketChannel`s, which by
design ignore the JVM's `socksProxyHost`/`socksProxyPort` properties — unlike `new Socket()`,
setting those changes nothing. DHT is UDP through a bundled mldht stack that has no SOCKS5
UDP-associate either. Retrofitting it means overriding a Guice provider and reimplementing a
package-private-heavy connection factory, for one of the two transports. 1.10 is also the
final release, so there is no version to upgrade into.

**An HTTP proxy would leak anyway.** It only carries TCP. DHT, and a good deal of peer
traffic, is UDP and would go around it — which is the classic torrent-proxy misconfiguration,
and it fails silently.

**And the protocol is not on our side.** BitTorrent announces an address to the tracker and
then connects directly to every peer in the swarm. That is the protocol working, not a leak.
Anyone in the swarm sees the address of everyone else in it; that is exactly how the provider
log added in [ADR-0014](0014-a-provider-log-in-sqlite.md) sees peers, and it is how they see
us.

## Decision

Confine the process to a tunnel at the network layer, and be exact about what that buys.

- **A gluetun sidecar, as an overlay.** `deploy/docker-compose.vpn.yml` layers onto the base
  stack rather than replacing it, so the ordinary deployment still runs with no VPN and no
  credentials. The API joins gluetun's network namespace with
  `network_mode: service:gluetun`, so **every** packet it sends leaves through the tunnel —
  DHT's UDP included, without the application knowing a tunnel exists.

- **The kill switch fails closed.** gluetun's firewall drops anything not going through the
  tunnel, so a dropped VPN means no traffic rather than traffic in the clear. This takes the
  whole API offline with it, because the whole API shares that namespace. That is the trade,
  and it is the right way round: a kill switch that fails open is not one. Keeping the API
  serving finished video while only acquisition is confined would need acquisition in a
  container of its own, which is a larger change than this.

- **Two holes, both necessary and both narrow.** `FIREWALL_OUTBOUND_SUBNETS` admits the
  compose network so the API can still reach Redis and nginx can still reach the API — which
  is why that network's subnet is pinned rather than left to Docker's pool, since a firewall
  rule cannot name a subnet nobody has allocated yet.

- **nginx is not reconfigured.** gluetun takes the network alias `streaming-api`, so the five
  existing `proxy_pass http://streaming-api:8080` directives keep resolving — to the same
  place, one hop earlier. A container sharing another's namespace has no DNS entry of its own,
  so there is nothing to collide with.

- **The application-level settings are hardening, not hiding.** Under the `docker` profile,
  MSE encryption is required rather than preferred (a "preferred" plaintext fallback is the
  other end's decision to make, so an observer only has to talk to us to opt out of it), and
  both Local Service Discovery and Peer Exchange are off. `acceptor-address` remains for the
  case where a tunnel is run on the host instead of in a sidecar: it binds outgoing
  connections as well as the listening socket, and is the only setting in the application that
  changes which address a peer sees.

## Consequences

- **This is not anonymity, and the documentation says so in every place it appears.** The VPN
  provider still sees the traffic; anyone able to compel or compromise them is back where they
  started. What it does is stop trackers and peers seeing this machine's address, which is
  real, and is all that was claimed.
- Credentials live in `deploy/vpn.env`, gitignored, from a committed
  `vpn.env.example`. gluetun supports most commercial providers and the required keys differ
  per provider.
- Peer connectivity is worse behind a tunnel without a forwarded port: inbound connections
  cannot reach us, so the swarm is outbound-only and finds fewer peers. Disabling PEX under
  the `docker` profile costs more on top of that. Both are deliberate; a provider that
  forwards a port recovers most of the first.
- The base stack is unchanged and still starts with no VPN, no `vpn.env` and no gluetun image
  pulled. Nothing here is on by default.
- The local `bind=` the library chooses when nothing is configured is whatever interface it
  finds first, which on a developer machine with Docker installed is frequently a bridge
  address rather than the real egress. It works, but it is not a considered choice — which is
  the other reason `acceptor-address` exists.
