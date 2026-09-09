package com.azt.streaming.providers.infrastructure;

import com.azt.streaming.providers.domain.PeerLocation;
import com.maxmind.geoip2.DatabaseReader;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns an address into a place and a network, from local {@code .mmdb} files.
 *
 * <p>Two databases because MaxMind's format keeps them apart: city and country in one, autonomous
 * system in the other. Either may be absent, and both usually are — the files need downloading and
 * a licence, so the default configuration has neither and peers are recorded without location
 * rather than not recorded at all.
 *
 * <p>No lookup leaves this process. That is a property worth keeping: resolving peer addresses
 * against a web service would hand a third party the list of everyone this machine downloads from,
 * which is a worse privacy leak than the one the rest of this work is trying to close.
 *
 * <p>{@code DatabaseReader} is thread-safe and memory-maps its file, so one instance is shared.
 */
@Slf4j
public class GeoIpEnricher implements Closeable {

    /**
     * Recent lookups, because a swarm is a few hundred addresses seen thousands of times each. Small
     * and crudely evicted: the point is to skip repeated work inside one download, not to be a
     * cache with a policy.
     */
    private static final int CACHE_SIZE = 4_096;

    private final DatabaseReader cityReader;
    private final DatabaseReader asnReader;

    private final Map<String, PeerLocation> cache = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, PeerLocation> eldest) {
            return size() > CACHE_SIZE;
        }
    });

    public GeoIpEnricher(DatabaseReader cityReader, DatabaseReader asnReader) {
        this.cityReader = cityReader;
        this.asnReader = asnReader;
    }

    /** Where {@code ipAddress} appears to be, or {@link PeerLocation#UNKNOWN} if nothing is known. */
    public PeerLocation locate(String ipAddress) {
        if (cityReader == null && asnReader == null) {
            return PeerLocation.UNKNOWN;
        }
        return cache.computeIfAbsent(ipAddress, this::lookup);
    }

    private PeerLocation lookup(String ipAddress) {
        try {
            InetAddress address = InetAddress.getByName(ipAddress);
            String countryCode = null;
            String country = null;
            String city = null;
            Long asn = null;
            String network = null;
            Double latitude = null;
            Double longitude = null;
            Integer accuracyRadiusKm = null;

            if (cityReader != null) {
                var response = cityReader.tryCity(address);
                if (response.isPresent()) {
                    countryCode = response.get().getCountry().getIsoCode();
                    country = response.get().getCountry().getName();
                    city = response.get().getCity().getName();

                    // Carried together deliberately: the radius is what makes the coordinates
                    // honest, and a caller that gets one without the other will draw a point where
                    // there is only a region.
                    var location = response.get().getLocation();
                    if (location != null) {
                        latitude = location.getLatitude();
                        longitude = location.getLongitude();
                        accuracyRadiusKm = location.getAccuracyRadius();
                    }
                }
            }
            if (asnReader != null) {
                var response = asnReader.tryAsn(address);
                if (response.isPresent()) {
                    asn = response.get().getAutonomousSystemNumber();
                    network = response.get().getAutonomousSystemOrganization();
                }
            }
            return new PeerLocation(
                    countryCode, country, city, asn, network, latitude, longitude, accuracyRadiusKm);
        } catch (Exception e) {
            // An address absent from the database is normal, not an error worth a stack trace: the
            // free tiers do not cover every allocation, and private ranges are in none of them.
            log.debug("No geolocation for {}: {}", ipAddress, e.toString());
            return PeerLocation.UNKNOWN;
        }
    }

    @Override
    public void close() throws IOException {
        if (cityReader != null) {
            cityReader.close();
        }
        if (asnReader != null) {
            asnReader.close();
        }
    }
}
