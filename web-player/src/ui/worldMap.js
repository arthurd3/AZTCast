/**
 * The provenance map: where the peers that served a video appear to be.
 *
 * There is no tile layer, and that is the design rather than a shortcut. Tiles come from someone
 * else's server, which would mean telling a third party which parts of the world are being looked
 * at — the exact thing the provider log avoids by reading geolocation from a file on this disk.
 * Country outlines are compiled into the build instead, so the page makes no external request at
 * all and works with no internet.
 *
 * It also settles the precision question for us. GeoIP returns a city or region centroid with an
 * accuracy radius of tens to hundreds of kilometres, and the database vendors say plainly that the
 * coordinates must not be read as a street address. A map that cannot zoom past country level
 * cannot imply one. What is drawn is a circle the size of the stated accuracy, under a dot sized by
 * how much that place actually served.
 *
 * The projection is equirectangular rather than the usual Web Mercator, which having no tiles is
 * what makes possible — tiles would have to be cut for the projection, GeoJSON simply reprojects.
 * Two reasons. It puts the world at a native 2:1, which is close to the shape of the box it has to
 * fill, where Mercator's 1:1 left two thirds of a wide container as empty ocean. And Mercator
 * inflates area by four times at 60° of latitude and around fifteen at 75°, so a dot on Siberia or
 * Greenland would read as far more of the world than it is — and area is exactly what a reader
 * compares on a map of counts and bytes.
 */
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { feature } from 'topojson-client';
import worldTopology from 'world-atlas/countries-110m.json';
import { formatBytes } from './format.js';

/**
 * The drawn world, cropped south of the Antarctic coast and north of the ice.
 *
 * The crop is what makes the aspect work: 360° by 138° is 2.61:1, and the container is about
 * 2.65:1, so the fitted world fills it. Nothing is lost — no address has ever resolved to either
 * cropped band.
 */
const BOUNDS = L.latLngBounds([-60, -180], [78, 180]);

/** Far enough to separate neighbouring cities, not far enough to suggest a street. */
const MAX_ZOOM = 6;

/**
 * A floor low enough never to be the binding constraint while measuring.
 *
 * Both {@link L.Map#getBoundsZoom} and {@link L.Map#fitBounds} clamp their result to the map's
 * current minimum, so the minimum has to be out of the way before either is asked anything.
 */
const UNCONSTRAINED_ZOOM = -5;

const MIN_RADIUS_PX = 5;
const MAX_RADIUS_PX = 26;

export function createWorldMap(container, { onSelect } = {}) {
  const map = L.map(container, {
    // Equirectangular. See the note at the top of this file — it is the projection, not the styling,
    // that decides whether the world fills its box.
    crs: L.CRS.EPSG4326,
    // No tiles, so nothing to attribute and nothing to fetch.
    attributionControl: false,
    worldCopyJump: false,
    maxBounds: BOUNDS,
    maxBoundsViscosity: 1,
    maxZoom: MAX_ZOOM,
    zoomControl: true,
    // Fractional zoom, and load-bearing. Leaflet's default snaps the fitted zoom down to a whole
    // number, and the fit here lands just under one — so the world was rounded to half its size and
    // sat in the middle of a container twice as wide. With the projection fixed but this left at
    // its default, the gap comes straight back.
    zoomSnap: 0,
    // Off, because this map sits in the middle of a page people scroll past. With it on, a wheel
    // gesture aimed at the document zooms the map instead and the reader loses their place in
    // both. Dragging, double-click and the +/- control all still zoom.
    scrollWheelZoom: false,
  });

  fitWorld();

  const countries = unwrapAntimeridian(feature(worldTopology, worldTopology.objects.countries));
  L.geoJSON(countries, {
    // Styled from the same tokens as everything else, read off the document so the map follows the
    // theme rather than hard-coding a palette a second time.
    style: () => ({
      color: token('--border'),
      weight: 1,
      fillColor: token('--surface-raised'),
      fillOpacity: 1,
      interactive: false,
    }),
  }).addTo(map);

  const markers = L.layerGroup().addTo(map);

  /**
   * Sizes the view to the world, and makes that the furthest out anyone can go.
   *
   * The floor is derived rather than declared: a hardcoded `minZoom` is a number that was right for
   * one container width and silently wrong for every other, which is how the map ended up pinned at
   * its own floor showing a half-size world.
   *
   * It is also dropped *before* fitting, which is not fussiness — `fitBounds` clamps to the current
   * minimum. A floor left over from a wider container is higher than the zoom a narrower one needs,
   * so the fit silently failed to zoom out and the map stayed where it was: shrink the window and
   * you were left staring at Africa. Measure unconstrained, then set the floor to whatever the fit
   * actually chose.
   */
  function fitWorld() {
    map.setMinZoom(UNCONSTRAINED_ZOOM);
    map.fitBounds(BOUNDS, { animate: false });
    map.setMinZoom(map.getZoom());
  }

  /**
   * Re-fit when the box changes shape.
   *
   * Leaflet measures its container once, at construction. This one is built at module load, so
   * every later resize — a window drag, an orientation change, a scrollbar appearing — left the map
   * sized for a box that no longer existed until the page was reloaded. cosmos.js watches its own
   * canvas the same way.
   */
  const resizeObserver = new ResizeObserver(() => {
    map.invalidateSize({ animate: false });
    fitWorld();
  });
  resizeObserver.observe(container);

  /** Redraws every dot. Places are already grouped by the API — one entry is one location. */
  function render(places) {
    markers.clearLayers();
    if (!places.length) {
      fitWorld();
      return;
    }

    const busiest = Math.max(...places.map(weight));

    places.forEach((place) => {
      const connected = place.connectedCount > 0;

      // The accuracy radius first and underneath, so the dot is read as sitting inside an area
      // rather than marking a point. Drawn in metres, which is what the data actually claims.
      if (place.accuracyRadiusKm) {
        L.circle([place.latitude, place.longitude], {
          radius: place.accuracyRadiusKm * 1000,
          color: token('--accent'),
          weight: 1,
          opacity: 0.25,
          fillColor: token('--accent'),
          fillOpacity: 0.06,
          interactive: false,
        }).addTo(markers);
      }

      L.circleMarker([place.latitude, place.longitude], {
        radius: radiusFor(weight(place), busiest),
        color: connected ? token('--accent') : token('--text-dim'),
        weight: connected ? 2 : 1,
        fillColor: connected ? token('--accent') : token('--text-dim'),
        // A peer that sent bytes is a different fact from an address a tracker merely named, so
        // the two do not get to look the same.
        fillOpacity: connected ? 0.55 : 0.2,
      })
        .bindPopup(popup(place))
        .on('click', () => onSelect?.(place))
        .addTo(markers);
    });
  }

  function destroy() {
    resizeObserver.disconnect();
    markers.clearLayers();
    map.remove();
  }

  return { render, destroy };
}

/**
 * Makes rings that cross the dateline contiguous again.
 *
 * Three countries in this dataset have a ring running from about +180 to about -180: Fiji, Russia
 * and Antarctica. Read literally, that ring is a shape 360 degrees wide, and it draws as a band
 * straight across the map — Fiji's was a hairline through the South Pacific and out both edges.
 *
 * The repair is to stop reading the longitudes as absolute. Walking each ring and keeping every
 * point within half a turn of the one before it turns +179 -> -179 into +179 -> +181, so the ring
 * stays where it belongs and simply continues past the seam, where the container clips it.
 * Latitudes are untouched.
 *
 * Cheaper and more predictable than the general fix, which is to cut every polygon along the
 * meridian and needs a geometry library. This dataset is fixed and compiled in, so the general
 * case is not one that can arrive later.
 */
function unwrapAntimeridian(collection) {
  const unwrapRing = (ring) => {
    let previous = ring[0][0];
    return ring.map(([longitude, latitude], index) => {
      if (index === 0) {
        return [longitude, latitude];
      }
      let continuous = longitude;
      while (continuous - previous > 180) {
        continuous -= 360;
      }
      while (previous - continuous > 180) {
        continuous += 360;
      }
      previous = continuous;
      return [continuous, latitude];
    });
  };

  collection.features.forEach((country) => {
    const { type, coordinates } = country.geometry;
    if (type === 'Polygon') {
      country.geometry.coordinates = coordinates.map(unwrapRing);
    } else if (type === 'MultiPolygon') {
      country.geometry.coordinates = coordinates.map((polygon) => polygon.map(unwrapRing));
    }
  });
  return collection;
}

/** Bytes served, falling back to peer count for places that only ever appeared in a peer list. */
function weight(place) {
  return place.bytesDownloaded > 0 ? place.bytesDownloaded : place.peerCount;
}

/**
 * Area, not radius, scales with the value — a circle drawn with twice the radius reads as four
 * times as much, which would overstate every large place on the map.
 */
function radiusFor(value, busiest) {
  if (busiest <= 0) {
    return MIN_RADIUS_PX;
  }
  const scaled = Math.sqrt(value / busiest);
  return MIN_RADIUS_PX + scaled * (MAX_RADIUS_PX - MIN_RADIUS_PX);
}

function popup(place) {
  const root = document.createElement('div');
  root.className = 'atlas__popup';

  const where = document.createElement('strong');
  where.textContent =
    [place.city, place.country ?? place.countryCode].filter(Boolean).join(', ') ||
    'Local desconhecido';
  root.appendChild(where);

  if (place.networks?.length) {
    root.appendChild(line(place.networks.join(' · ')));
  }
  root.appendChild(
    line(
      `${place.peerCount} ${place.peerCount === 1 ? 'par' : 'pares'} · ` +
        `${place.connectedCount} conectado${place.connectedCount === 1 ? '' : 's'}`,
    ),
  );
  if (place.bytesDownloaded > 0) {
    root.appendChild(line(`${formatBytes(place.bytesDownloaded)} recebidos daqui`));
  }

  // The caveat travels with the coordinate, always. A number this imprecise shown without it
  // invites the reader to believe a precision that was never there.
  const caveat = document.createElement('em');
  caveat.className = 'atlas__caveat';
  caveat.textContent = place.accuracyRadiusKm
    ? `Local aproximado — precisão de ~${place.accuracyRadiusKm} km`
    : 'Local aproximado';
  root.appendChild(caveat);

  return root;
}

function line(text) {
  const element = document.createElement('span');
  element.textContent = text;
  return element;
}

/** Reads a design token off the document, so the map cannot drift from the rest of the palette. */
function token(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}
