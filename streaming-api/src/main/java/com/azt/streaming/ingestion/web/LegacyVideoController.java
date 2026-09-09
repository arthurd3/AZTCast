package com.azt.streaming.ingestion.web;

import com.azt.streaming.ingestion.application.IngestionService;
import com.azt.streaming.ingestion.web.dto.CreateStreamJobRequest;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pre-refactor ingestion endpoint, kept working for an external client.
 *
 * <p>An out-of-repo Python bot POSTs here, and the videoId it needs exists <em>only</em> inside the
 * Portuguese sentence this returns — there is no JSON field and never was. If that client extracts
 * the id with a regex, changing the body would hand it a 202 it cannot parse: an apparent success
 * with nothing extracted, failing silently, in somebody else's repository.
 *
 * <p>So the body is preserved byte for byte, accents included. Do not "fix" the language, the
 * spacing or the punctuation. What is added is strictly additive and cannot break a parser: a
 * Location header pointing at the new resource, and RFC 8594 deprecation signalling.
 *
 * <p>Delete this class once the bot has migrated to {@link IngestionController}. See
 * docs/decisions/0006-deprecate-video-download-endpoint.md.
 */
@RestController
@RequestMapping("/api/v1/video")
@Deprecated(since = "0.1.0", forRemoval = true)
public class LegacyVideoController {

    /** RFC 8594. Advertised removal date; revisit once the bot reports it has migrated. */
    private static final String SUNSET = "Wed, 31 Mar 2027 23:59:59 GMT";

    private final IngestionService ingestionService;

    public LegacyVideoController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping(value = "/download", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> downloadTorrentLink(
            @Valid @RequestBody final CreateStreamJobRequest request) {

        String videoId = ingestionService.startIngestion(request.magnetUrl()).videoId();

        // Byte-for-byte identical to the pre-refactor response. See the class javadoc.
        String streamUrl = "/api/v1/stream/" + videoId + "/master.m3u8";
        String responseBody = "Download iniciado. O stream estará disponível em: " + streamUrl;

        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/videos/" + videoId))
                .header("Deprecation", "true")
                .header("Sunset", SUNSET)
                .header("Link", "</api/v1/videos>; rel=\"successor-version\"")
                .body(responseBody);
    }
}
