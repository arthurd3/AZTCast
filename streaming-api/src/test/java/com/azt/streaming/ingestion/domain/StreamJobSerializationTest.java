package com.azt.streaming.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StreamJob} is written to Redis as JSON, which makes its shape a wire format.
 *
 * <p>This exists because a derived accessor broke it once: adding {@code isInFlight()} made Jackson
 * emit an extra {@code "inFlight"} property, and reading the job back then failed on an unknown
 * field. The failure only appeared against a real Redis, and only for jobs written after the change
 * — the worst combination to debug. These tests make it a unit-test failure instead.
 */
class StreamJobSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final StreamJob JOB = StreamJob.downloading(
                    "2724a02c-f275-49d2-8389-e76bfeacd4c6", "magnet:?xt=urn:btih:abc", Instant.parse("2026-09-08T12:00:00Z"))
            .failed("no seeders", Instant.parse("2026-09-08T12:30:00Z"));

    @Test
    void roundTripsThroughJson() throws Exception {
        String json = objectMapper.writeValueAsString(JOB);

        assertThat(objectMapper.readValue(json, StreamJob.class)).isEqualTo(JOB);
    }

    @Test
    @DisplayName("only the record's own components are serialised")
    void doesNotSerialiseDerivedAccessors() {
        // A derived accessor is computed from the components, so writing it is redundant — and any
        // that Jackson picks up but cannot map back on read is a hard deserialisation failure.
        assertThat(objectMapper.valueToTree(JOB).fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder(
                        "videoId",
                        "status",
                        "progressPercent",
                        "transcodePercent",
                        "magnetUrl",
                        "failureReason",
                        "createdAt",
                        "updatedAt");
    }

    @Test
    @DisplayName("a job written before progressPercent existed still reads back")
    void toleratesJsonWithoutProgress() throws Exception {
        // Redis keys live for seven days, so an upgrade is guaranteed to read records written by the
        // previous version. A missing component has to default rather than throw, or every job in
        // flight during a deploy becomes unreadable — the same class of failure this file was
        // written for, arriving from the opposite direction.
        String legacy =
                """
                {"videoId":"v1","status":"DOWNLOADING","magnetUrl":"magnet:?xt=urn:btih:abc",\
                "failureReason":null,"createdAt":"2026-09-08T12:00:00Z","updatedAt":"2026-09-08T12:00:00Z"}\
                """;

        StreamJob job = objectMapper.readValue(legacy, StreamJob.class);

        assertThat(job.videoId()).isEqualTo("v1");
        assertThat(job.progressPercent()).isZero();
    }

    @Test
    void writesInstantsAsReadableIsoStrings() throws Exception {
        // So `redis-cli GET` on a job key is legible to a human debugging an incident.
        assertThat(objectMapper.writeValueAsString(JOB)).contains("2026-09-08T12:00:00Z");
    }
}
