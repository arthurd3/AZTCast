package com.azt.streaming.transcoding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

import com.azt.streaming.transcoding.domain.MediaProbe;
import com.azt.streaming.transcoding.domain.MediaTranscoder;
import com.azt.streaming.transcoding.domain.ProbedSource;
import com.azt.streaming.transcoding.domain.ProbedVideo;
import com.azt.streaming.transcoding.infrastructure.ProcessRunner;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Guards the two ways {@code @Async} fails silently here.
 *
 * <p>Neither throws, and neither shows up in any other test:
 *
 * <ul>
 *   <li>If {@code @EnableAsync} is dropped — it used to be declared twice, so removing "the
 *       duplicate" can easily remove both — every call becomes synchronous and the ingestion
 *       request blocks for the whole encode. That looks like a network timeout, not a config bug.
 *   <li>If the {@code @Async} qualifier stops matching the executor bean name, Spring silently falls
 *       back to Boot's auto-configured {@code applicationTaskExecutor}: different bounds, different
 *       thread names, no error.
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(AsyncTranscodingIntegrationTest.DecoyExecutorConfiguration.class)
class AsyncTranscodingIntegrationTest {

    /**
     * A second {@link Executor}, present only so the qualifier is observable.
     *
     * <p>It must be a {@link TaskExecutor}, not merely an {@code Executor}: Spring's default
     * resolution asks for {@code TaskExecutor} by type first, so a plain {@code Executor} decoy
     * creates no ambiguity at all. With two TaskExecutors an unqualified {@code @Async} cannot
     * resolve by type, finds no bean named {@code taskExecutor}, and falls back to a
     * SimpleAsyncTaskExecutor whose threads are named differently — which this test catches.
     * Verified by mutation: dropping the qualifier fails this test.
     */
    @TestConfiguration
    static class DecoyExecutorConfiguration {
        @Bean
        TaskExecutor decoyExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setThreadNamePrefix("decoy-");
            executor.initialize();
            return executor;
        }
    }

    @Autowired private MediaTranscoder mediaTranscoder;

    @MockitoBean private ProcessRunner processRunner;

    /**
     * Mocked at the port, not at {@link ProcessRunner}: this test is about where the work runs, and
     * the transcoder now probes before it encodes. Stubbing the transport instead would make the
     * test depend on ffprobe's output format for no reason.
     */
    @MockitoBean private MediaProbe mediaProbe;

    @Test
    void transcodingRunsOnTheNamedTranscodingExecutor() {
        given(mediaProbe.probe(any())).willReturn(ProbedVideo.measured(true, "Main", 31));
        given(mediaProbe.probeSource(any()))
                .willReturn(new ProbedSource(
                        true, "h264", "Main", 31, 1280, 720, 24, 3000, 10, List.of(), List.of()));

        AtomicReference<String> workerThread = new AtomicReference<>();
        willAnswer(
                        invocation -> {
                            workerThread.set(Thread.currentThread().getName());
                            return null;
                        })
                .given(processRunner)
                .run(any(), any(), any());

        mediaTranscoder.transcodeToHls(Path.of("/tmp/source.mkv"), "async-probe", percent -> {}).join();

        assertThat(workerThread.get())
                .as("must run off the caller thread — otherwise @Async is not applied at all")
                .isNotEqualTo(Thread.currentThread().getName())
                .as("must be the transcoding pool, not an unqualified fallback executor")
                .startsWith("test-transcode-");
    }
}
