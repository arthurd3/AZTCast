package com.azt.streaming.playback.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.azt.streaming.playback.domain.HlsAsset;
import com.azt.streaming.support.PropertiesFixture;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

class HlsResponseFactoryTest {

    private static HlsAsset asset(String videoId, String fileName) {
        return new HlsAsset(videoId, fileName, Path.of("/tmp", videoId, fileName), 10, Instant.EPOCH);
    }

    private static HlsResponseFactory offloading(String internalPrefix) {
        return new HlsResponseFactory(PropertiesFixture.defaults()
                .offloadEnabled(true)
                .internalPrefix(internalPrefix)
                .build());
    }

    @Test
    void percentEncodesEachPathSegmentOfTheRedirect() {
        // Containment was already proven upstream; this is about spelling a name correctly in a
        // header. An unencoded space would truncate the URI nginx re-dispatches to.
        ResponseEntity<Resource> response = offloading("/_media").toResponse(asset("video id", "720p 000.m4s"));

        assertThat(response.getHeaders().getFirst(HlsResponseFactory.X_ACCEL_REDIRECT))
                .isEqualTo("/_media/video%20id/720p%20000.m4s");
    }

    @Test
    void doesNotLetAnEncodedSeparatorEscapeTheInternalLocation() {
        // Even a name that survived containment must not be able to re-open the path structure once
        // it is written into the redirect header.
        ResponseEntity<Resource> response = offloading("/_media").toResponse(asset("id", "../../etc/passwd"));

        assertThat(response.getHeaders().getFirst(HlsResponseFactory.X_ACCEL_REDIRECT))
                .isEqualTo("/_media/id/..%2F..%2Fetc%2Fpasswd");
    }

    @Test
    void toleratesATrailingSlashOnTheConfiguredPrefix() {
        // /_media and /_media/ mean the same location to a reader; they must not produce a double
        // slash that matches nothing.
        assertThat(offloading("/_media/")
                        .toResponse(asset("id", "a.m4s"))
                        .getHeaders()
                        .getFirst(HlsResponseFactory.X_ACCEL_REDIRECT))
                .isEqualTo("/_media/id/a.m4s");
    }

    @Test
    void writesTheBodyItselfWhenOffloadIsDisabled() {
        ResponseEntity<Resource> response = new HlsResponseFactory(PropertiesFixture.defaults().build())
                .toResponse(asset("id", "a.m4s"));

        assertThat(response.getHeaders().getFirst(HlsResponseFactory.X_ACCEL_REDIRECT)).isNull();
        assertThat(response.getBody()).isNotNull();
        // nginx spells this "hex(mtime)-hex(size)"; size 10 at the epoch is "0-a".
        assertThat(response.getHeaders().getETag()).isEqualTo("\"0-a\"");
    }
}
