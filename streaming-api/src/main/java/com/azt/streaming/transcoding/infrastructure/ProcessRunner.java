package com.azt.streaming.transcoding.infrastructure;

import com.azt.streaming.transcoding.domain.TranscodingException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
     * Ceiling on captured stdout. ffprobe's JSON for one file is a few hundred bytes; this exists so
     * a command that unexpectedly streams cannot turn a probe into an OutOfMemoryError.
     */
    private static final int MAX_CAPTURED_CHARS = 1024 * 1024;

    /**
     * Runs {@code command}, streaming its merged stdout/stderr to the DEBUG log.
     *
     * @throws TranscodingException if the command fails, times out, or the thread is interrupted
     */
    public void run(List<String> command, Duration timeout) {
        execute(command, timeout, null, line -> {});
    }

    /**
     * Runs {@code command} and shows every line of its output to {@code watcher} as it arrives.
     *
     * <p>For {@code ffmpeg -progress pipe:1}, whose whole value is being read while the process is
     * still running. The watcher runs on the drain thread, so it must not block: anything slow there
     * stops the pipe being emptied, and a pipe nobody empties is a process that hangs.
     *
     * @throws TranscodingException if the command fails, times out, or the thread is interrupted
     */
    public void run(List<String> command, Duration timeout, Consumer<String> watcher) {
        execute(command, timeout, null, watcher);
    }

    /**
     * Runs {@code command} and returns its stdout.
     *
     * <p>Separate from {@link #run} because that one merges stderr into stdout — right when the
     * output is only ever a log, wrong when it is a result to parse. ffprobe writes its JSON to
     * stdout and its diagnostics to stderr; merged, the JSON is unparseable. Here the two are kept
     * apart: stdout is returned, stderr still feeds the failure message.
     *
     * @throws TranscodingException if the command fails, times out, or the thread is interrupted
     */
    public String runCapturing(List<String> command, Duration timeout) {
        StringBuilder captured = new StringBuilder();
        execute(command, timeout, captured, line -> {});
        return captured.toString();
    }

    /**
     * @param captured when null, stderr is merged into stdout and only the tail is kept; when
     *     non-null, the streams are separate and stdout is appended here
     */
    private void execute(
            List<String> command, Duration timeout, StringBuilder captured, Consumer<String> watcher) {
        log.debug("Running: {}", String.join(" ", command));

        boolean merged = captured == null;
        Process process = start(command, merged);
        Deque<String> tail = new ArrayDeque<>(TAIL_LINES);

        // Drained on separate threads: reading in the calling thread would block past the deadline
        // if the process goes quiet without exiting, and would deadlock on a full pipe.
        Consumer<String> stdout = merged ? line -> keepTail(tail, line) : line -> capture(captured, line);
        Thread drainOut = drainThread(process.getInputStream(), "out-" + process.pid(), stdout.andThen(watcher));

        // With the streams unmerged, stderr has its own pipe, and a pipe nobody drains is a hang
        // rather than a lost message — ffmpeg alone can fill it before it produces a frame.
        Thread drainErr =
                merged ? null : drainThread(process.getErrorStream(), "err-" + process.pid(), line -> keepTail(tail, line));

        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                destroy(process);
                throw new TranscodingException("%s timed out after %s:%n%s"
                        .formatted(command.getFirst(), timeout, joinTail(tail)));
            }
            drainOut.join(REAP_GRACE.toMillis());
            if (drainErr != null) {
                drainErr.join(REAP_GRACE.toMillis());
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new TranscodingException("%s exited with code %d:%n%s"
                        .formatted(command.getFirst(), exitCode, joinTail(tail)));
            }
        } catch (InterruptedException e) {
            destroy(process);
            // Restore the flag: swallowing it left callers unable to see the cancellation.
            Thread.currentThread().interrupt();
            throw new TranscodingException("Interrupted while running " + command.getFirst(), e);
        }
    }

    /**
     * The kept tail as one block, read under the same monitor that writes it.
     *
     * <p>Joining it unsynchronised raced the drain thread: on the timeout path that thread is still
     * running when the message is built, and {@code String.join} over a deque being mutated throws
     * {@link java.util.ConcurrentModificationException} — turning a useful timeout diagnostic into
     * an unrelated crash.
     */
    private static String joinTail(Deque<String> tail) {
        synchronized (tail) {
            return String.join("\n", tail);
        }
    }

    private static void keepTail(Deque<String> tail, String line) {
        synchronized (tail) {
            if (tail.size() == TAIL_LINES) {
                tail.removeFirst();
            }
            tail.addLast(line);
        }
    }

    private static void capture(StringBuilder captured, String line) {
        synchronized (captured) {
            if (captured.length() < MAX_CAPTURED_CHARS) {
                captured.append(line).append('\n');
            }
        }
    }

    private Process start(List<String> command, boolean mergeErrorStream) {
        try {
            return new ProcessBuilder(command).redirectErrorStream(mergeErrorStream).start();
        } catch (IOException e) {
            throw new TranscodingException(
                    "Could not start '%s' — is it installed and on PATH?".formatted(command.getFirst()), e);
        }
    }

    private Thread drainThread(InputStream stream, String name, Consumer<String> sink) {
        Thread drain = new Thread(
                () -> {
                    try (BufferedReader reader =
                            new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            log.debug("[{}] {}", name, line);
                            sink.accept(line);
                        }
                    } catch (IOException e) {
                        log.debug("Output stream closed for {}", name, e);
                    }
                },
                "process-" + name);
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
