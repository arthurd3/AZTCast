package com.azt.streaming.transcoding.infrastructure;

import com.azt.streaming.transcoding.domain.TranscodingException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Runs an external command with a deadline and guarantees the process is reaped.
 *
 * <p>The previous inline version had none of that: it read the merged output stream to EOF and then
 * called {@code waitFor()} with no timeout, so an ffmpeg that hung — waiting on a malformed input,
 * for instance — blocked one of the two transcoding threads forever with no way to recover short of
 * restarting the service. It also printed every line of ffmpeg output to {@code System.out}.
 */
@Component
@Slf4j
public class ProcessRunner {

    /** Lines of output kept for the failure message. ffmpeg puts the useful error last. */
    private static final int TAIL_LINES = 20;

    /** Grace period for a killed process to actually die before we stop waiting on it. */
    private static final Duration REAP_GRACE = Duration.ofSeconds(5);

    /**
     * Runs {@code command}, streaming its merged stdout/stderr to the DEBUG log.
     *
     * @throws TranscodingException if the command fails, times out, or the thread is interrupted
     */
    public void run(List<String> command, Duration timeout) {
        log.debug("Running: {}", String.join(" ", command));

        Process process = start(command);
        Deque<String> tail = new ArrayDeque<>(TAIL_LINES);

        // Drained on a separate thread: reading in the calling thread would block past the
        // deadline if the process goes quiet without exiting, and would deadlock on a full pipe.
        Thread drain = startDrainThread(process, tail);

        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                destroy(process);
                throw new TranscodingException(
                        "%s timed out after %s".formatted(command.getFirst(), timeout));
            }
            drain.join(REAP_GRACE.toMillis());

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new TranscodingException(
                        "%s exited with code %d:%n%s"
                                .formatted(command.getFirst(), exitCode, String.join("\n", tail)));
            }
        } catch (InterruptedException e) {
            destroy(process);
            // Restore the flag: swallowing it left callers unable to see the cancellation.
            Thread.currentThread().interrupt();
            throw new TranscodingException("Interrupted while running " + command.getFirst(), e);
        }
    }

    private Process start(List<String> command) {
        try {
            return new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new TranscodingException(
                    "Could not start '%s' — is it installed and on PATH?".formatted(command.getFirst()), e);
        }
    }

    private Thread startDrainThread(Process process, Deque<String> tail) {
        Thread drain =
                new Thread(
                        () -> {
                            try (BufferedReader reader =
                                    new BufferedReader(
                                            new InputStreamReader(
                                                    process.getInputStream(), StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    log.debug("[{}] {}", process.pid(), line);
                                    synchronized (tail) {
                                        if (tail.size() == TAIL_LINES) {
                                            tail.removeFirst();
                                        }
                                        tail.addLast(line);
                                    }
                                }
                            } catch (IOException e) {
                                log.debug("Output stream closed for pid {}", process.pid(), e);
                            }
                        },
                        "process-output-" + process.pid());
        drain.setDaemon(true);
        drain.start();
        return drain;
    }

    private void destroy(Process process) {
        process.destroyForcibly();
        try {
            process.waitFor(REAP_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
