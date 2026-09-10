package com.azt.streaming.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.azt.streaming.ingestion.application.IngestionService;
import com.azt.streaming.ingestion.web.IngestionController;
import com.azt.streaming.shared.storage.VideoCatalog;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * The check {@link WebCorsConfiguration} says it needs.
 *
 * <p>Its allowed-method list is a hand-maintained echo of what the controllers implement, and
 * getting it wrong fails in the least helpful way there is: {@code DefaultCorsProcessor} answers a
 * bare 403 before any handler runs, so the server log is silent and the player shows a number. It
 * has already happened twice — once when ingestion's {@code POST} was missing, and again when
 * keeping a video added {@code PUT} and {@code DELETE}.
 *
 * <p>So this does not restate the list. It reads the methods the controller actually declares and
 * demands a preflight succeed for each, which means the next mutating endpoint is covered by a test
 * written before it exists.
 */
@WebMvcTest(IngestionController.class)
@Import(WebCorsConfiguration.class)
@EnableConfigurationProperties(StreamingProperties.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "aztcast.streaming.web.allowed-origins=http://localhost:5173")
class WebCorsConfigurationTest {

    private static final String ORIGIN = "http://localhost:5173";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private IngestionService ingestionService;
    @MockitoBean private VideoCatalog videoCatalog;

    @Test
    @DisplayName("every mutating method the controller declares survives a preflight")
    void everyMutatingMethodIsAllowedThroughCors() throws Exception {
        Set<RequestMethod> mutating = declaredMutatingMethods();

        // A guard on the guard: if the scan ever finds nothing this test would pass by doing
        // nothing at all, which is the failure mode of every reflective test.
        assertThat(mutating)
                .as("the scan found no mutating endpoints, so it is not testing anything")
                .isNotEmpty();

        for (RequestMethod method : mutating) {
            int status = mockMvc.perform(MockMvcRequestBuilders.options("/api/v1/videos/some-id/keep")
                            .header("Origin", ORIGIN)
                            .header("Access-Control-Request-Method", method.name()))
                    .andReturn()
                    .getResponse()
                    .getStatus();

            assertThat(status)
                    .as(
                            "%s is declared by a controller but missing from WebCorsConfiguration's"
                                    + " allowedMethods, so the browser gets a bare 403",
                            method)
                    .isEqualTo(200);
        }
    }

    @Test
    @DisplayName("an origin nobody allowed is still refused")
    void anUnknownOriginIsRejected() throws Exception {
        // The other half: the list is a policy, not a formality. Without this, the fix for the case
        // above could be a wildcard and this file would still be green.
        int status = mockMvc.perform(MockMvcRequestBuilders.options("/api/v1/videos/some-id/keep")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "PUT"))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status).isEqualTo(403);
    }

    /** HTTP methods the controller declares that are not plain reads. */
    private static Set<RequestMethod> declaredMutatingMethods() {
        Set<RequestMethod> found = new TreeSet<>();
        for (Method method : IngestionController.class.getDeclaredMethods()) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping == null) {
                continue;
            }
            Arrays.stream(mapping.method())
                    .filter(m -> m != RequestMethod.GET && m != RequestMethod.HEAD && m != RequestMethod.OPTIONS)
                    .forEach(found::add);
        }
        return found;
    }
}
