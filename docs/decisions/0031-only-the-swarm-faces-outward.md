# Only the swarm faces outward

## Status

Accepted

Refines [ADR-0015](0015-ip-masking-belongs-to-the-network.md), which established that the
BitTorrent side is exposed by the protocol itself and that confining it is the network's job. That
still holds. What this adds is the other half of the sentence: if the swarm port is what has to
face the world, then nothing else should, and the HTTP surface should say so in configuration
rather than in a README paragraph asking politely.

## Context

The security section said "there is no authentication — run it on a trusted network only". That
described a posture without enforcing one. The shipped configuration did the opposite of what the
paragraph asked: `deploy/docker-compose.yml` published nginx on `0.0.0.0:8000`, and local
development bound `0.0.0.0:8080` with actuator details on. Anyone on the same network could reach
`POST /api/v1/videos` and make the machine download a torrent.

The deployment this actually has is one person's computer. It has two surfaces and they have
opposite needs, which nothing in the configuration distinguished:

- **The swarm port** must be reachable from the internet or peers behind NAT cannot dial in, and
  the swarm sees the address either way. This is ADR-0015's subject and its answer is the VPN
  overlay.
- **The HTTP control surface** has no such need at all. It is the interface that starts downloads
  and, since ADR-0030, deletes videos. Nobody off this host has any business reaching it.

Binding to loopback is most of that. It is also weaker than it sounds, and the weakness is worth
naming because it is the reason this record exists rather than a one-line change to a port mapping.
The attacker who reaches a loopback service is the browser, which runs code from every page its
owner visits and is already inside:

- **DNS rebinding.** A page resolves its own name to an address it controls, waits for the browser
  to cache the origin, then rebinds the name to `127.0.0.1`. The browser now treats that page as
  same-origin with whatever is listening there. The same-origin policy is not violated; it is
  satisfied, on the attacker's terms. Transmission's RPC had exactly this, and so did the MCP
  TypeScript SDK as recently as CVE-2025-66414.
- **CORS does not stop a request happening.** A form-encoded `POST` is a "simple" request: the
  browser sends it, the side effect lands, and only the response is withheld from the page. For an
  API whose side effects are "download this torrent" and "delete this video", being denied the
  response is no consolation.

## Decision

The control surface is closed from outside and unchanged from inside.

- **Loopback is the default bind.** `server.address: 127.0.0.1`, and compose publishes
  `127.0.0.1:8000`. `WEB_BIND` and `server.address` open it deliberately. The `docker` profile
  binds `0.0.0.0` inside the container and this is not an exception to the rule: 8080 is never
  published to the host, so the container's namespace is the boundary, and binding loopback there
  would hide the API from nginx rather than harden anything.

- **A `Host` allowlist, checked in `AllowedHostFilter`.** Requests naming a host we did not agree
  to answer to get 403. This is the whole defence against rebinding, and it works because the one
  thing the attacking page cannot forge is the name it needed to use to set the trick up. The list
  is configuration and not inference — a list derived from the request would contain whatever the
  request claimed.

- **The header is read raw, not through `getServerName()`.** The first version did the latter, on
  the reasonable-sounding grounds that the container has already stripped the port and unwrapped an
  IPv6 literal. It was wrong, and a running instance proved it: under the `docker` profile
  `forward-headers-strategy: framework` is on so the rate limiter can see real client addresses,
  `ForwardedHeaderFilter` is ordered ahead of this one, and it rewrites `getServerName()` from
  `X-Forwarded-Host`. A request with `Host: rebind.invalid` and `X-Forwarded-Host: localhost`
  answered 200. A header a proxy set is the proxy's claim; the `Host` line is the request's own, and
  a check whose whole premise is "this value cannot be forged" has to read the unforgeable one. The
  port and brackets are now parsed here, and a test pins the bypass shut.

- **An `Origin` check on `POST`, `PUT`, `DELETE` and `PATCH`,** in the same filter, against the
  same list. This is CSRF protection without tokens, which is the right shape for a service with no
  sessions to forge.

- **A missing `Origin` is allowed, and that is the design rather than a hole in it.** Browsers
  attach the header to exactly the cross-origin requests worth refusing. `curl`, the smoke test and
  every other local tool send nothing and are untouched. Nothing here asks the person at the
  keyboard for a credential, a token or a click.

- **Empty lists disable the checks,** on the same terms as an empty `allowed-origins` disables
  CORS: restrictive by default, and switched off in one obvious place rather than drifted out of.

## Consequences

- **Reaching this from another device now takes two changes, not one.** A bind address and an
  entry in `allowed-hosts`. Changing only the first produces a 403 that looks like a bug and is the
  check working, which is the most likely way someone will meet this record. The compose file and
  `application.yml` both say so at the point of change.

- **Ordering with `ForwardedHeaderFilter` was a trap, and might be one again.** Reading the raw
  header sidesteps it for `Host`. Anything else this filter ever wants to know about the request's
  origin is subject to the same question, and the answer will not always be as easy.

- **The filter runs before Spring MVC, so it writes its own problem document.** An ArchUnit rule
  keeps everything out of `shared.error`, and no `@RestControllerAdvice` would see what a filter
  throws in any case. That is one hand-written JSON literal, duplicating a shape the error boundary
  otherwise owns. It is the price of checking this early enough to be worth checking at all.

- **This does not add authentication, and is not a substitute for it.** Anyone who can reach the
  port can still drive the whole API. What changed is who can reach the port. Authentication remains
  the most valuable next change, and is now the difference between "safe on your machine" and "safe
  anywhere" rather than the only thing standing between a LAN guest and your disk.

- **The magnet is still only `@NotBlank`.** A caller-supplied `&x.pe=` is passed to the engine, so
  whoever can call the endpoint can make it dial an arbitrary `IP:port`, loopback and RFC1918
  included, and `&tr=` is a denylist rather than an allowlist. Loopback binding plus the `Origin`
  check narrows "whoever" to the person at the keyboard, which is why this was not urgent enough to
  ride along here. It is not fixed, and the security section says so.

- **Rate limiting is still absent from the default profile.** It needs Redis, which is off by
  default. Bounding a caller who is by construction the machine's owner was never what that limiter
  was for.
