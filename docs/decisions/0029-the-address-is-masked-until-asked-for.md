# The address is masked until it is asked for

## Status

Accepted

Amends [ADR-0016](0016-the-provenance-map-is-drawn-offline.md), which put `addressPeersSee` on
the provenance page and set the offline-lookup rule that this change now explains in prose to
the reader. Nothing there is reversed. It also gives
[ADR-0015](0015-ip-masking-belongs-to-the-network.md) a reader-facing home: that record decided
where masking actually happens and required its limits be repeated wherever the subject comes
up, and this is where they are repeated for the person looking at the page.

## Context

The provenance page carries one tile that is not about the swarm. `addressPeersSee` is this
machine's own public address, as some remote peer reported it in the `yourip` field of the
BEP-10 extended handshake — the only direct evidence available that outbound traffic is masked,
which is why ADR-0016 put it there.

It is also the most prominent thing on the page: two grid columns wide, accent border and tint,
monospace at a size nothing else in the strip uses. That prominence was deliberate and is still
right. What was never decided is that it should be legible before anyone asked.

The value is not a secret. Every peer in the swarm has it; that is the entire premise of
ADR-0015. But the threat this closes is not a peer, it is a screenshot. This is precisely the
page someone opens to check whether their tunnel is working, which makes it the page most likely
to be on a shared screen, in a bug report, or in a message to whoever set the tunnel up — and in
every one of those the address the tunnel exists to hide is the largest text on it. Every other
address on the page belongs to a stranger and is the page's subject; this one belongs to the
reader and is incidental to it.

**Partial masking was considered and rejected.** `170.246.211.***` looks careful and is not: the
first three octets are the allocation, and the allocation is exactly what a geolocation database
resolves against. A partial mask surrenders very nearly everything the whole value would to the
thing this page is warning about.

Separately, the page had been asserting three things — regions and not addresses, nothing leaves
this machine, the tile is what peers report — with nowhere for a reader to find out why any of
them held, or how wrong the estimate is allowed to be.

## Decision

**The tile arrives masked.** A fixed-length run of twelve bullets, never derived from the
address: a run matching the real length would say whether this machine is on IPv4 or IPv6, which
is a good part of what there is to hide. Revealing takes a deliberate press of a native
`<button>` carrying `aria-pressed`, at the full 44×44 tap target, with an accessible name that
does not change when the state does.

**Nothing is persisted.** The reveal lives in a closure and the tile is rebuilt on every render,
so it re-masks whenever the page reloads *and* whenever expanding a video re-runs `load()`.
Storing a "revealed" preference would be storing a decision to leak, and this fails in the safe
direction: the cost of the reset is one click.

**Peer addresses stay in the clear.** Masking them would empty the page of its subject, and they
are public facts of a public swarm — visible to every participant by construction.

**The explanation gets its own page**, `/localizacao.html`, linked from inside the tile, from the
sentence on the provenance page that makes the claim, and from every footer. It covers `yourip`,
the four ways a peer address is learned, what an RIR allocation is and is not, how a commercial
geolocation database is assembled, the accuracy radius and why the map draws circles, what this
application does not do, and how to change what peers actually see.

## Consequences

Reading your own address costs one click, on purpose. Nobody can infer IPv4 from IPv6 by looking
at the mask — and, as the direct cost of that, nobody can tell a masked tile from a *reported*
one without clicking either. An address that has not been reported yet still renders no tile at
all, unchanged.

**This is obfuscation, not secrecy, and the record says so plainly so that nobody later believes
otherwise.** The value is in the DOM, in the API response, in the network tab, and sourcemaps are
published. It defends against a shoulder, a screenshot and a screen share. Masking that changes
what a peer sees remains ADR-0015's subject, and the page says as much where a reader will
actually meet the claim.

A fifth page and a fifth Rollup input, plus one small stylesheet for long-form prose — the first
in this player, because the existing pages carry their measure as an inline style on a single
paragraph and that does not survive nine sections. That sheet is also the one place that puts
back the list markers `reset.css` strips globally.

The accuracy figures on the explanation page are one vendor's, at one moment, and will drift.
They are stated with their source beside them so the next reader can re-check rather than
re-derive. The page does not link `docs/decisions/*.md`: those files are not served to a browser
in development or in production, so the records are named in prose instead.

There is still no test runner for the player (ADR-0011, restated in ADR-0028), so the toggle's
keyboard path, its announced state, and the re-mask on re-render are verified by hand.
