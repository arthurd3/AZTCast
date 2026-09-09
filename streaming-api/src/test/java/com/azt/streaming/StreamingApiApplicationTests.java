package com.azt.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.azt.streaming.shared.config.StreamingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class StreamingApiApplicationTests {

    @Autowired private StreamingProperties properties;

    @Test
    void contextLoads() {
        assertThat(properties).isNotNull();
    }

    /**
     * Guards the migration away from the old {@code dir.files.*} keys: if a nested block fails to
     * bind, the context still starts but the record arrives with nulls.
     */
    @Test
    void bindsEveryConfigurationBlock() {
        assertThat(properties.storage().hlsDir()).isNotNull();
        assertThat(properties.storage().downloadsDir()).isNotNull();
        assertThat(properties.ffmpeg().binary()).isNotBlank();
        assertThat(properties.ffmpeg().renditions()).isNotEmpty();
        assertThat(properties.torrent().videoExtensions()).isNotEmpty();
        assertThat(properties.transcoding().pool().coreSize()).isPositive();
        assertThat(properties.web().allowedOrigins()).isNotNull();
    }
}
