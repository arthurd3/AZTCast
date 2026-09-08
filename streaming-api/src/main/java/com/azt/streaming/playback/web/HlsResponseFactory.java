package com.azt.streaming.playback.web;

import com.azt.streaming.playback.domain.HlsAsset;
import com.azt.streaming.playback.domain.HlsMediaTypes;
import com.azt.streaming.shared.config.StreamingProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

/**
 * Turns a located {@link HlsAsset} into an HTTP response, in one of two modes.
 *
 * <p><b>Offload on:</b> the response is headers only, carrying {@code X-Accel-Redirect}. nginx
 * discards it, re-dispatches internally to a location that has the media volume mounted, and writes
 * the file with {@code sendfile}. The JVM is out of the byte path entirely — it authorises and
 * returns, rather than holding a Tomcat thread for the length of a segment transfer.
 *
 * <p><b>Offload off:</b> the response carries a {@code FileSystemResource} and Spring writes the
 * bytes, exactly as this service always did. This is not a fallback so much as the mode that keeps
 * {@code mvn spring-boot:run} working with no nginx in front, which is how the service is developed.
 *
 * <p>The two modes deliberately differ on validators. Whoever writes the bytes owns {@code ETag} and
 * {@code Last-Modified}: in offload mode nginx generates its own from the file it serves, so an
 * {@code ETag} set here would simply be replaced — and a client revalidating with nginx's value
 * against ours would never match. So we set validators only when we are the one answering.
 */
@Component
public class HlsResponseFactory {

    static final String X_ACCEL_REDIRECT = "X-Accel-Redirect";

    /**
     * Immutable assets get the conventional one-year ceiling: HTTP caches are not required to honour
     * anything longer, so a larger number would only look bolder.
     */
    private static final CacheControl IMMUTABLE =
            CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable();

    /**
     * Playlists are re-read constantly by the player but do change when media is regenerated. A
     * short max-age with stale-while-revalidate keeps them fresh without making every poll a
     * blocking origin fetch; stale-if-error keeps a stream playing through an origin wobble.
     */
    private static final CacheControl PLAYLIST = CacheControl.maxAge(60, TimeUnit.SECONDS)
            .cachePublic()
            .staleWhileRevalidate(Duration.ofMinutes(5))
            .staleIfError(Duration.ofDays(1));

    private final boolean offloadEnabled;
    private final String internalPrefix;

    public HlsResponseFactory(StreamingProperties properties) {
        this.offloadEnabled = properties.playback().offloadEnabled();
        String prefix = properties.playback().internalPrefix();
        this.internalPrefix = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    public ResponseEntity<Resource> toResponse(HlsAsset asset) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(HlsMediaTypes.forFileName(asset.fileName()))
                .cacheControl(asset.isImmutable() ? IMMUTABLE : PLAYLIST);

        if (offloadEnabled) {
            return response.header(X_ACCEL_REDIRECT, internalUri(asset)).build();
        }

        // Returning a Resource is load-bearing, not incidental: Spring's message-converter path
        // recognises it and adds Range support for free — Accept-Ranges, 206 via
        // HttpRange.toResourceRegions, 416 on a bad range. Switching to StreamingResponseBody or
        // InputStreamResource to "stream properly" would silently drop all of it.
        return response.eTag(asset.eTag())
                .lastModified(asset.lastModified())
                .contentLength(asset.sizeBytes())
                .body(new FileSystemResource(asset.path()));
    }

    /**
     * Builds the internal URI nginx re-dispatches to.
     *
     * <p>Both components are encoded per path segment even though {@link
     * com.azt.streaming.shared.storage.MediaStorage} has already proven containment. That check
     * decided which file may be read; this encoding decides how a name is spelled in a header, and
     * conflating the two is how a second traversal hole gets in later. Encoding here also means a
     * name containing a space or a percent reaches nginx as the file it actually is.
     */
    private String internalUri(HlsAsset asset) {
        return "%s/%s/%s"
                .formatted(
                        internalPrefix,
                        UriUtils.encodePathSegment(asset.videoId(), StandardCharsets.UTF_8),
                        UriUtils.encodePathSegment(asset.fileName(), StandardCharsets.UTF_8));
    }
}
