# An equirectangular map, and signals derived rather than stored

## Status

Accepted

Refines [ADR-0016](0016-the-provenance-map-is-drawn-offline.md), which decided the map has no tiles.
That decision stands; this is what it turned out to require.

## Context

**The map filled a third of its box.** Not a styling slip — two causes compounding, and worth the
arithmetic because neither is obvious from looking at it:

- The drawn world was cropped to 138° of latitude, which in Web Mercator is 0.568 of the world's
  height against the full 1.0 of its width. In a container that is wide and short, latitude is
  therefore the binding constraint, and the horizontal axis is left with slack.
- With the real box, ~1440 × 544: width permitted zoom ≤ 2.49, height ≤ 1.90. Leaflet takes the
  minimum — and then **`zoomSnap` defaults to 1 and floors 1.90 to 1**, discarding almost a factor
  of two. At zoom 1 the world is 512 px wide inside 1440: roughly 460 px of empty ocean on each
  side, which is what a reader sees as "the map is broken".

**Three countries were drawn as bands across the whole map.** Fiji, Russia and Antarctica each have
a ring running from about +180 to about −180. Read literally that is a shape 360° wide, and it
renders as a hairline straight through the map and out both edges.

**And several signals worth showing were things we already had.** `bytesUploaded` and
`connectedSeconds` were read off the wire, persisted, served over the API — and never rendered.

## Decision

### The map

- **`L.CRS.EPSG4326`.** Equirectangular, built into Leaflet, and available *only because there is no
  tile layer* — tiles are cut for a projection, GeoJSON simply reprojects. It puts the world at a
  native 2:1. Against the cropped bounds that is 2.61:1, and the container is shaped to match, so
  the fit is near-exact: measured afterwards at **100% of the container width**, against roughly a
  third before.
- **`zoomSnap: 0`.** Without it the fractional fit is floored straight back to an integer and the
  gap returns. One line, and the one that matters most.
- **The zoom floor is derived, and dropped before measuring.** A hardcoded `minZoom` is a number
  that is right for one container and silently wrong for every other. Worse, both `fitBounds` and
  `getBoundsZoom` *clamp to the current minimum* — so a floor left over from a wide container stops
  a narrow one from ever zooming out again, and shrinking the window left the reader staring at
  Africa. Measure unconstrained, then set the floor to whatever the fit chose.
- **A `ResizeObserver` re-fits.** Leaflet measures its container once, at construction, and this map
  is built at module load; every later resize left it sized for a box that no longer existed.
- **Rings are unwrapped, not cut.** Walking each ring and keeping every point within half a turn of
  the one before turns +179 → −179 into +179 → +181, so the shape stays where it belongs and
  continues past the seam, where the container clips it. The general fix is to cut polygons along
  the meridian, which needs a geometry library; the dataset here is fixed and compiled in, so the
  general case cannot arrive later.

Equirectangular is also the more honest projection for this map, and that is the second reason
rather than a rationalisation of the first: Mercator inflates area fourfold at 60° of latitude and
around fifteenfold at 75°. The map plots counts and bytes, and area is exactly what a reader
compares.

### Signals derived on read

- **`networkKind`** — whether an address looks like a datacenter or a VPN exit rather than a home —
  is computed from the ASN and operator name **at read time, and never stored**. A heuristic will be
  wrong and will be improved; deriving it means an improvement applies to every row already written
  instead of only to rows written afterwards. It is also why no migration was needed for it.
  Classification is a bundled list, because a commercial detection API would mean sending a third
  party every peer we talk to — the precise leak that reading geolocation from a local file avoids.
- **`videosServed`** — the same address appearing in more than one download — is a subquery, not a
  column, for the same reason: it is a fact about the whole table that changes as the table grows.
- **`capabilities`** is stored, in one comma-separated column rather than a boolean each. A new
  protocol flag is then a value, not a migration, and nothing here ever queries for one flag alone.

### One merge rule changed

`connected_seconds` now merges with `MAX` rather than by adding. Waiting for the disconnect event to
measure a connection looked right and recorded nothing: those events arrive around the moment a
torrent stops being tracked, so the one observation that knew the duration was the one most likely
to be dropped. It is sampled while the connection is open instead — at which point each sighting
reports the duration *so far*, and adding them would count the same seconds repeatedly.

## Consequences

- The container carries an `aspect-ratio` matching the cropped world, so the fit holds at every
  width rather than at the one it was tuned for. Below about 580 px the `min-height` wins and the
  map letterboxes vertically instead, which is the better way round to fail.
- `bytesDownloaded ÷ connectedSeconds` is shown as an average rate **only when a peer connected
  exactly once**. Bytes merge with `MAX` and seconds now do too, but across reconnects they may come
  from different sessions; rather than print a quotient that quietly understates, the rest omit it.
- Protocol capability flags are rendered as quiet text, not badges. Nearly every modern client
  reports all five, so as pills they were five identical boxes repeated down every row — a lot of
  width spent saying the same thing, which pushed the columns that *do* differ off the edge.
- The hosting list will age. It is a record of operators that actually turned up in swarms plus the
  large clouds, not a registry, and it is tested against real residential carriers precisely because
  the failure that matters is calling somebody's home connection a datacenter.
- The zero-external-requests property of ADR-0016 is unchanged and was re-checked: twenty-one
  requests on a full page load with a video expanded, all to the origin.
