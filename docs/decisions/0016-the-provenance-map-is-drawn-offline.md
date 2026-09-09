# The provenance map is drawn offline

## Status

Accepted

## Context

[ADR-0014](0014-a-provider-log-in-sqlite.md) started recording every peer that served every
video, with a country, a city and a network operator for each. It was only ever visible as a
live table under the progress bar, which disappears when the download does — while the rows
stay for thirty days with nothing to read them.

A page for them wants a map. Two things about a map made that a decision rather than a task.

**A map normally means tiles, and tiles come from someone else.** Every pan and zoom is a
request to a tile server, which learns which parts of the world this machine is looking at,
attached to its address. ADR-0014's flat rule is that no lookup leaves the process — a
geolocation lookup is a read of a local file specifically so that resolving peers does not
hand a third party the list of everyone this machine downloads from. Fetching tiles to draw
those same peers would give away the same thing by the back door.

**The coordinates do not support a map that can zoom.** GeoIP returns a city or region
centroid with an accuracy radius of tens to hundreds of kilometres. The vendors say plainly
that the coordinates must not be used to identify a street address, and that they should be
shown with the radius beside them. Studies of the free tiers find a long tail misplaced by
thousands of kilometres, and unknown addresses dumped into a handful of default locations.
Many distinct addresses resolve to the *identical* point.

## Decision

Draw the map from data compiled into the build, and design it around the precision the data
actually has.

- **No tile layer at all.** Country outlines come from Natural Earth (public domain, via the
  ISC-licensed `world-atlas` redistribution) bundled at build time: 38 kB beside Leaflet's
  42 kB, together about a seventh of hls.js, and only on this page. The result makes no
  external request, so the page works with no internet and tells nobody what is being looked
  at. Verified by loading it with the network panel open: nineteen requests, all to the
  origin.

- **Zoom stops well short of a street**, which the absent tile layer enforces for free. The
  honest granularity and the privacy-preserving choice turn out to be the same choice.

- **One dot per place, not per peer.** Grouped in SQL by coordinate rounded to ~1 km. Because
  many peers share a centroid, per-peer pins would stack invisibly and imply forty separate
  buildings where the data claims one city. Dot **area** — not radius — scales with bytes
  served, so a large place is not overstated by a factor of its own size.

- **The accuracy radius is drawn underneath**, and every popup says *aproximado*. Where the
  database gives no radius — DB-IP Lite does not, GeoLite2 City does — the caveat stays and
  the number goes.

- **A dot that sent bytes does not look like one that never connected.** An address a tracker
  named and nobody ever reached is the weakest row in the log, and it should not draw the eye
  like a peer that served 50 MB.

- **Leaflet, but only its interaction.** `L.circleMarker`, never `L.marker`, which sidesteps
  the broken-image-icon problem under bundlers entirely and is the right mark for a
  count-scaled dot anyway. Wheel zoom is off: the map sits inside a page people scroll past,
  and a wheel gesture aimed at the document should not zoom the map instead.

## Consequences

- The page also carries `addressPeersSee`, from the `yourip` field of peers' extended
  handshakes. It is the only direct evidence available that the tunnel in
  [ADR-0015](0015-ip-masking-belongs-to-the-network.md) is masking anything — everything else
  on that subject is configuration describing what ought to happen, where this is a remote
  peer reporting what it saw.
- **Leaflet's stylesheet is unlayered**, so it beats every rule in `@layer components`
  regardless of specificity. The overrides for its classes therefore sit outside the layer, at
  the bottom of `atlas.css`, and the file says so — inside it they lost silently and the map
  rendered with a grey ocean under a dark page. Relatedly, `leaflet-container` lands on the
  *same* element passed to `L.map()`, not a child, so those selectors are compound.
- `provider_peer` gained six nullable columns, applied by an `ALTER TABLE` migration guarded
  on `PRAGMA table_info`. `CREATE TABLE IF NOT EXISTS` does nothing to a table that already
  exists, so on every install but the first the migration is the only part that runs — which
  is why it has a test that builds the *old* schema and upgrades it.
- Byte counters are sampled from the download's own poll loop rather than written when a peer
  connects or disconnects. Both of those are edges where the number is uninteresting: a
  connection has transferred nothing when it opens, and by the time it closes the download is
  usually over. This was found by shipping it and reading zeroes.
- The live table on the library page and this one now share a renderer, so a column added to
  one appears in both.
- Still no frontend test runner, so the page is covered by manual verification — including
  the network-panel check above, which is the one that proves the claim this ADR makes.
