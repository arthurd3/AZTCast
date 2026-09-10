package com.azt.streaming.acquisition.infrastructure;

import com.azt.streaming.acquisition.domain.PeerObservation;
import com.azt.streaming.acquisition.domain.PeerObservationSink;
import com.azt.streaming.acquisition.domain.TorrentDownloadException;
import com.azt.streaming.acquisition.domain.TorrentDownloader;
import com.azt.streaming.shared.config.StreamingProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link TorrentDownloader} that hands the swarm to a separate process and reads back what it saw.
 *
 * <p>Nothing about BitTorrent happens here. This class opens a Unix socket, writes one request, and
 * turns a stream of JSON lines into the callbacks the rest of the pipeline already expects. That is
 * the point of it: the code parsing bytes from strangers is on the other end of the socket, in a
 * process that can write to the downloads directory and do nothing else — not reach Redis, not read
 * the HLS root, not open a port that is not the one the swarm needs. See ADR-0032.
 *
 * <p>A Unix socket rather than a TCP port on loopback. There is no address for anything on this
 * machine to connect to, only a path with a mode on it, so who may drive the engine is a filesystem
 * question with a filesystem answer. It also means the engine is not reachable by a browser, which
 * ADR-0031 had to spend a filter on for the API itself.
 *
 * <p><b>The connection is the download.</b> One socket per magnet, opened when it starts and closed
 * when it ends, which is why there are no request ids anywhere in the protocol. Closing it is also
 * how a download is cancelled: the engine notices its client has gone and abandons the torrent.
 */
@Slf4j
public class BrokeredTorrentDownloader implements TorrentDownloader {

    private final Path socketPath;
    private final PeerObservationSink peerObservations;
    private final BrokeredSwarmSelfView selfView;
    private final MagnetTrackerInjector trackerInjector;
    private final StreamingProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public BrokeredTorrentDownloader(
            PeerObservationSink peerObservations,
            BrokeredSwarmSelfView selfView,
            MagnetTrackerInjector trackerInjector,
            StreamingProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.socketPath = properties.torrent().engineSocket();
        this.peerObservations = peerObservations;
        this.selfView = selfView;
        this.trackerInjector = trackerInjector;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public CompletableFuture<Path> download(
            String videoId, String magnetUrl, Path targetDirectory, IntConsumer onProgress) {

        CompletableFuture<Path> result = new CompletableFuture<>();
        SocketChannel channel;
        try {
            channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(UnixDomainSocketAddress.of(socketPath));
        } catch (IOException e) {
            // Failed before anything started, so it fails here rather than on the reader thread:
            // "the engine is not running" is a different kind of problem from "the swarm went quiet",
            // and a caller that never gets a future cannot tell them apart.
            result.completeExceptionally(
                    new TorrentDownloadException("Torrent engine is not reachable on " + socketPath, e));
            return result;
        }

        // Closing the socket is the cancellation signal, so every exit runs it — success, failure and
        // timeout alike. Same shape as the embedded path releasing its client from whenComplete.
        result.whenComplete((path, error) -> closeQuietly(channel, videoId));
        result.orTimeout(properties.torrent().downloadTimeout().toMillis(), TimeUnit.MILLISECONDS);

        // A platform thread per download, not a pool. Each one spends its life blocked on a socket
        // that speaks a few hundred times over several hours, and the count is bounded by what the
        // API will ingest at once. A pool here would only add a queue that delays downloads.
        Thread reader = new Thread(
                () -> pump(channel, videoId, magnetUrl, targetDirectory, onProgress, result),
                "torrent-engine-" + videoId);
        reader.setDaemon(true);
        reader.start();

        log.info("Started brokered download {} into {}", videoId, targetDirectory);
        log.debug("Magnet for {}: {}", videoId, magnetUrl);
        return result;
    }

    private void pump(
            SocketChannel channel,
            String videoId,
            String magnetUrl,
            Path targetDirectory,
            IntConsumer onProgress,
            CompletableFuture<Path> result) {

        AtomicReference<String> infoHash = new AtomicReference<>("");
        try {
            channel.write(StandardCharsets.UTF_8.encode(startRequest(videoId, magnetUrl, targetDirectory) + "\n"));

            try (BufferedReader lines = new BufferedReader(
                    new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (result.isDone()) {
                        return;
                    }
                    handle(objectMapper.readTree(line), videoId, infoHash, onProgress, result, targetDirectory);
                    if (result.isDone()) {
                        return;
                    }
                }
            }
            // The stream ended without a terminal message. The engine died, was restarted, or the
            // socket was closed under it; either way this download is not going to finish, and
            // saying so beats a future that never completes.
            result.completeExceptionally(
                    new TorrentDownloadException("Torrent engine closed the connection for " + videoId));
        } catch (TorrentDownloadException e) {
            // Already says what went wrong, and in one case says something that matters: a file
            // reported outside the directory the engine was given. Re-wrapping it in a generic
            // "engine failed" would bury the one message here worth reading twice.
            result.completeExceptionally(e);
        } catch (IOException | RuntimeException e) {
            result.completeExceptionally(new TorrentDownloadException("Torrent engine failed for " + videoId, e));
        }
    }

    private void handle(
            JsonNode message,
            String videoId,
            AtomicReference<String> infoHash,
            IntConsumer onProgress,
            CompletableFuture<Path> result,
            Path targetDirectory) {

        switch (message.path("type").asText()) {
            case "started" -> infoHash.set(message.path("infoHash").asText(""));
            case "progress" -> onProgress.accept(message.path("percent").asInt());
            case "self" -> selfView.report(message.path("address").asText(null));
            case "peer" -> peerObservations.record(observation(message, videoId, infoHash.get()));
            case "done" -> result.complete(requireInside(targetDirectory, message.path("file").asText("")));
            case "error" ->
                result.completeExceptionally(
                        new TorrentDownloadException(message.path("message").asText("engine reported a failure")));
            default -> log.debug("Ignoring unknown engine message for {}: {}", videoId, message);
        }
    }

    /**
     * The path the engine reported, proven to be inside the directory we asked it to write to.
     *
     * <p>The engine is the least trusted thing in this system — it is the part with its hands in the
     * swarm, and it is sandboxed precisely because it might one day be doing something other than
     * what it was told. A path it returns is a claim, and this is the one place that claim turns into
     * a file the transcoder will open, so it is checked here rather than believed.
     */
    private static Path requireInside(Path targetDirectory, String reported) {
        if (reported.isBlank()) {
            throw new TorrentDownloadException("Torrent engine reported no file");
        }
        Path root = targetDirectory.toAbsolutePath().normalize();
        Path file = Path.of(reported).toAbsolutePath().normalize();
        if (!file.startsWith(root)) {
            throw new TorrentDownloadException("Torrent engine reported a file outside " + root + ": " + file);
        }
        return file;
    }

    private String startRequest(String videoId, String magnetUrl, Path targetDirectory) {
        StreamingProperties.Torrent torrent = properties.torrent();
        StreamingProperties.Network network = torrent.network();

        ObjectNode request = objectMapper.createObjectNode();
        request.put("type", "start");
        request.put("videoId", videoId);
        // Augmented here rather than in the engine, so the tracker list a magnet is given is decided
        // by the same tested code on both paths and the two cannot drift.
        request.put("magnet", trackerInjector.augmentToUri(magnetUrl));
        request.put("targetDir", targetDirectory.toAbsolutePath().toString());
        request.put("videoOnly", torrent.downloadVideoOnly());
        request.put("timeoutSeconds", torrent.downloadTimeout().toSeconds());
        torrent.videoExtensions().forEach(request.withArray("videoExtensions")::add);

        ObjectNode settings = request.putObject("network");
        settings.put("encryption", network.encryption().name());
        settings.put("disableLocalServiceDiscovery", network.disableLocalServiceDiscovery());
        settings.put("disablePeerExchange", network.disablePeerExchange());
        settings.put("acceptorAddress", network.acceptorAddress() == null ? "" : network.acceptorAddress());
        settings.put("acceptorPort", network.acceptorPort());
        settings.put("maxPeerConnectionsPerTorrent", network.maxPeerConnectionsPerTorrent());
        settings.put("maxActivePeerConnectionsPerTorrent", network.maxActivePeerConnectionsPerTorrent());
        settings.put("maxPeerConnections", network.maxPeerConnections());
        settings.put("peersPerTrackerRequest", network.peersPerTrackerRequest());
        settings.put("trackerTimeoutSeconds", network.trackerTimeout().toSeconds());
        return request.toString();
    }

    private PeerObservation observation(JsonNode message, String videoId, String infoHash) {
        Set<String> capabilities = new LinkedHashSet<>();
        message.path("capabilities").forEach(capability -> capabilities.add(capability.asText()));
        return new PeerObservation(
                videoId,
                infoHash,
                message.path("ip").asText(),
                message.path("port").asInt(),
                text(message, "client"),
                integer(message, "piecesComplete"),
                integer(message, "piecesTotal"),
                number(message, "bytesDownloaded"),
                number(message, "bytesUploaded"),
                integer(message, "connectedSeconds"),
                capabilities,
                PeerObservation.Kind.valueOf(message.path("kind").asText()),
                clock.instant());
    }

    /**
     * Absent stays absent.
     *
     * <p>{@code PeerObservation} uses boxed types so that "the peer has not said" and "the peer said
     * zero" are different answers, and defaulting a missing field to 0 would quietly turn every
     * silence into a claim. The engine omits what it does not know for the same reason.
     */
    private static Integer integer(JsonNode message, String field) {
        JsonNode value = message.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static Long number(JsonNode message, String field) {
        JsonNode value = message.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }

    private static String text(JsonNode message, String field) {
        JsonNode value = message.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String read = value.asText();
        return read.isBlank() ? null : read;
    }

    private static void closeQuietly(SocketChannel channel, String videoId) {
        try {
            channel.close();
        } catch (IOException e) {
            log.debug("Could not close the engine socket for {}", videoId, e);
        }
    }

}
