package com.azt.streaming.providers.domain;

/**
 * Where an address appears to be, and whose network it is on.
 *
 * <p>Inferred from a local database, never reported by the peer and never asked of anyone: a
 * geolocation lookup here is a read of a file on this disk. Accuracy is what a free IP database
 * gives — country is usually right, city often is not, and a peer behind a VPN or a carrier NAT
 * resolves to the operator rather than to anywhere the user has been.
 *
 * @param asn autonomous system number, or null. The AS is the most reliable field here: it is a
 *     routing fact rather than an estimate of a physical place.
 * @param latitude estimated latitude, or null. Read {@code accuracyRadiusKm} before believing it.
 * @param longitude estimated longitude, or null.
 * @param accuracyRadiusKm the radius, in kilometres, within which the address is claimed to lie —
 *     commonly tens to hundreds of kilometres, and the reason these coordinates are a
 *     <em>region</em>, not a point. The database vendors are explicit that they must not be used to
 *     identify a street or a household, and that they should never be displayed without this number
 *     beside them. Many distinct addresses resolve to one shared centroid, so identical coordinates
 *     mean "somewhere in this city", not "the same building".
 */
public record PeerLocation(
        String countryCode,
        String country,
        String city,
        Long asn,
        String network,
        Double latitude,
        Double longitude,
        Integer accuracyRadiusKm) {

    public static final PeerLocation UNKNOWN = new PeerLocation(null, null, null, null, null, null, null, null);

    /** Whether this can be drawn on a map at all. */
    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }
}
