package com.azt.streaming.acquisition.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azt.streaming.acquisition.domain.PeerObservation;
import com.azt.streaming.acquisition.domain.PeerObservationSink;
import com.azt.streaming.acquisition.domain.TorrentDownloadException;
import com.azt.streaming.shared.config.StreamingProperties;
import com.azt.streaming.support.PropertiesFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the brokered downloader against a fake engine that speaks the real protocol over a real
 * Unix socket.
 *
 * <p>A fake rather than a mock, and a real socket rather than an in-memory stream, because what is
 * worth testing here is the wire contract: that the start request carries what the engine needs,
 * that five kinds of event become the right callbacks, and that a hang-up is a failure rather than a
 * future nobody completes. Mocking the socket would test that this class calls the methods it calls.
 */
class BrokeredTorrentDownloaderTest {

    private static final String MAGNET = "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel";
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");

    @TempDir Path root;

    private ServerSocketChannel server;
    private Thread engine;

    @AfterEach
    void tearDown() throws IOException {
        if (server != null && server.isOpen()) {
            server.close();
        }
        if (engine != null) {
            engine.interrupt();
        }
    }

    /**
     * Stands up a fake engine. The handler receives the start request it was sent and a writer to
     * answer with; whatever it writes is what the downloader has to cope with.
     */
    private Path fakeEngine(Consumer<Exchange> handler) throws IOException {
        Path socketPath = root.resolve("engine.sock");
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socketPath));
        engine = new Thread(() -> {
            try (SocketChannel client = server.accept()) {
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(Channels.newInputStream(client), StandardCharsets.UTF_8));
                JsonNode start = new ObjectMapper().readTree(in.readLine());
                handler.accept(new Exchange(start, Channels.newOutputStream(client)));
            } catch (IOException e) {
                // The downloader closing the socket is an ordinary end to a fake engine's life.
            }
        });
        engine.setDaemon(true);
        engine.start();
        return socketPath;
    }

    private record Exchange(JsonNode start, OutputStream out) {
        void send(String line) {
            try {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private BrokeredTorrentDownloader downloader(Path socketPath, List<PeerObservation> observations) {
        StreamingProperties properties = PropertiesFixture.defaults()
                .engine(StreamingProperties.Engine.BROKERED)
                .engineSocket(socketPath)
                .build();
        return new BrokeredTorrentDownloader(
                observations::add,
                new BrokeredSwarmSelfView(),
                new MagnetTrackerInjector(properties),
                properties,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a finished download resolves to the file the engine reported")
    void completesWithTheReportedFile() throws Exception {
        Path target = root.resolve("v1");
        Path socketPath = fakeEngine(exchange -> {
            exchange.send("{\"type\":\"started\",\"infoHash\":\"abc\"}");
            exchange.send("{\"type\":\"progress\",\"percent\":50}");
            exchange.send("{\"type\":\"done\",\"file\":\"" + target.resolve("Movie.mkv") + "\"}");
        });

        List<Integer> progress = new CopyOnWriteArrayList<>();
        CompletableFuture<Path> result =
                downloader(socketPath, new CopyOnWriteArrayList<>()).download("v1", MAGNET, target, progress::add);

        assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(target.resolve("Movie.mkv"));
        assertThat(progress).containsExactly(50);
    }

    @Test
    @DisplayName("the start request carries what the engine needs to run the download")
    void startRequestIsComplete() throws Exception {
        Path target = root.resolve("v1");
        CompletableFuture<JsonNode> seen = new CompletableFuture<>();
        Path socketPath = fakeEngine(exchange -> {
            seen.complete(exchange.start());
            exchange.send("{\"type\":\"done\",\"file\":\"" + target.resolve("Movie.mkv") + "\"}");
        });

        downloader(socketPath, new CopyOnWriteArrayList<>()).download("v1", MAGNET, target, percent -> {});

        JsonNode start = seen.get(10, TimeUnit.SECONDS);
        assertThat(start.path("type").asText()).isEqualTo("start");
        assertThat(start.path("videoId").asText()).isEqualTo("v1");
        assertThat(start.path("targetDir").asText()).isEqualTo(target.toAbsolutePath().toString());
        // Augmented on this side, so both engines agree on the tracker list a magnet gets.
        assertThat(start.path("magnet").asText()).startsWith("magnet:?xt=urn:btih:");
        assertThat(start.path("network").path("acceptorPort").asInt()).isEqualTo(6891);
        assertThat(start.path("videoExtensions").isArray()).isTrue();
        // A real sink was injected, so the engine is asked for peer events.
        assertThat(start.path("peerEvents").asBoolean()).isTrue();
        // Both of these used to be accepted in configuration and never put on the wire.
        assertThat(start.path("network").path("maxPendingConnectionRequests").isMissingNode()).isFalse();
        assertThat(start.path("network").path("maxIoQueueSize").isMissingNode()).isFalse();
    }

    @Test
    @DisplayName("a no-op sink tells the engine not to bother reporting peers at all")
    void doesNotAskForPeerEventsNobodyWants() throws Exception {
        // The provider log is off by default, which binds the sink to PeerObservationSink.NONE.
        // The engine had no way to know that, so it built and wrote every sighting and this side
        // parsed every one to hand it to a lambda that discards it — about thirteen hundred events
        // for one download, for nothing.
        Path target = root.resolve("v1");
        CompletableFuture<JsonNode> seen = new CompletableFuture<>();
        Path socketPath = fakeEngine(exchange -> {
            seen.complete(exchange.start());
            exchange.send("{\"type\":\"done\",\"file\":\"" + target.resolve("Movie.mkv") + "\"}");
        });

        StreamingProperties properties = PropertiesFixture.defaults()
                .engine(StreamingProperties.Engine.BROKERED)
                .engineSocket(socketPath)
                .build();
        new BrokeredTorrentDownloader(
                        PeerObservationSink.NONE,
                        new BrokeredSwarmSelfView(),
                        new MagnetTrackerInjector(properties),
                        properties,
                        new ObjectMapper(),
                        Clock.fixed(NOW, ZoneOffset.UTC))
                .download("v1", MAGNET, target, percent -> {});

        assertThat(seen.get(10, TimeUnit.SECONDS).path("peerEvents").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("every kind of peer event reaches the sink, with absent fields left absent")
    void peerEventsBecomeObservations() throws Exception {
        Path target = root.resolve("v1");
        Path socketPath = fakeEngine(exchange -> {
            exchange.send("{\"type\":\"started\",\"infoHash\":\"08ada5\"}");
            exchange.send("{\"type\":\"peer\",\"kind\":\"DISCOVERED\",\"ip\":\"1.2.3.4\",\"port\":6881}");
            exchange.send("{\"type\":\"peer\",\"kind\":\"TRANSFER\",\"ip\":\"1.2.3.4\",\"port\":6881,"
                    + "\"bytesDownloaded\":4096,\"bytesUploaded\":0,\"client\":\"qBittorrent 5.0.4\","
                    + "\"capabilities\":[\"DHT\",\"PEX\"]}");
            exchange.send("{\"type\":\"done\",\"file\":\"" + target.resolve("Movie.mkv") + "\"}");
        });

        List<PeerObservation> observations = new CopyOnWriteArrayList<>();
        downloader(socketPath, observations).download("v1", MAGNET, target, percent -> {}).get(10, TimeUnit.SECONDS);

        assertThat(observations).hasSize(2);
        PeerObservation discovered = observations.get(0);
        assertThat(discovered.kind()).isEqualTo(PeerObservation.Kind.DISCOVERED);
        assertThat(discovered.videoId()).isEqualTo("v1");
        assertThat(discovered.infoHash()).isEqualTo("08ada5");
        assertThat(discovered.ipAddress()).isEqualTo("1.2.3.4");
        assertThat(discovered.observedAt()).isEqualTo(NOW);
        // Boxed on purpose: "the peer has not said" and "the peer said zero" are different answers,
        // and defaulting a missing field to 0 would turn every silence into a claim.
        assertThat(discovered.bytesDownloaded()).isNull();
        assertThat(discovered.client()).isNull();

        PeerObservation transfer = observations.get(1);
        assertThat(transfer.bytesDownloaded()).isEqualTo(4096L);
        assertThat(transfer.bytesUploaded()).isEqualTo(0L);
        assertThat(transfer.client()).isEqualTo("qBittorrent 5.0.4");
        assertThat(transfer.capabilities()).containsExactly("DHT", "PEX");
    }

    @Test
    @DisplayName("the address peers report is published through the port")
    void selfViewIsFed() throws Exception {
        Path target = root.resolve("v1");
        Path socketPath = fakeEngine(exchange -> {
            exchange.send("{\"type\":\"self\",\"address\":\"203.0.113.7\"}");
            exchange.send("{\"type\":\"done\",\"file\":\"" + target.resolve("Movie.mkv") + "\"}");
        });

        BrokeredSwarmSelfView selfView = new BrokeredSwarmSelfView();
        StreamingProperties properties = PropertiesFixture.defaults()
                .engine(StreamingProperties.Engine.BROKERED)
                .engineSocket(socketPath)
                .build();
        new BrokeredTorrentDownloader(
                        observation -> {},
                        selfView,
                        new MagnetTrackerInjector(properties),
                        properties,
                        new ObjectMapper(),
                        Clock.fixed(NOW, ZoneOffset.UTC))
                .download("v1", MAGNET, target, percent -> {})
                .get(10, TimeUnit.SECONDS);

        assertThat(selfView.addressPeersSee()).contains("203.0.113.7");
    }

    @Test
    @DisplayName("an error from the engine fails the download with the engine's reason")
    void errorsAreCarried() throws Exception {
        Path target = root.resolve("v1");
        Path socketPath = fakeEngine(exchange -> exchange.send(
                "{\"type\":\"error\",\"message\":\"timed out after 7200s\"}"));

        CompletableFuture<Path> result =
                downloader(socketPath, new CopyOnWriteArrayList<>()).download("v1", MAGNET, target, percent -> {});

        assertThatThrownBy(() -> result.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(TorrentDownloadException.class)
                .hasMessageContaining("timed out after 7200s");
    }

    @Test
    @DisplayName("a file outside the directory the engine was given is refused")
    void refusesAFileOutsideTheTarget() throws Exception {
        // The engine is the least trusted thing here — it is the part with its hands in the swarm.
        // A path it returns is a claim, and this is where the claim would become a file ffmpeg opens.
        Path target = root.resolve("v1");
        Path socketPath = fakeEngine(exchange -> exchange.send(
                "{\"type\":\"done\",\"file\":\"" + root.resolve("elsewhere/passwd") + "\"}"));

        CompletableFuture<Path> result =
                downloader(socketPath, new CopyOnWriteArrayList<>()).download("v1", MAGNET, target, percent -> {});

        assertThatThrownBy(() -> result.get(10, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(TorrentDownloadException.class)
                .hasMessageContaining("outside");
    }

    @Test
    @DisplayName("an engine that hangs up without finishing fails the download rather than hanging it")
    void aClosedConnectionIsAFailure() throws Exception {
        Path target = root.resolve("v1");
        Path socketPath = fakeEngine(exchange -> exchange.send("{\"type\":\"started\",\"infoHash\":\"abc\"}"));

        CompletableFuture<Path> result =
                downloader(socketPath, new CopyOnWriteArrayList<>()).download("v1", MAGNET, target, percent -> {});

        assertThatThrownBy(() -> result.get(10, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(TorrentDownloadException.class)
                .hasMessageContaining("closed the connection");
    }

    @Test
    @DisplayName("an engine that is not running fails immediately, not eventually")
    void anAbsentEngineFailsFast() {
        Path socketPath = root.resolve("nothing-here.sock");

        CompletableFuture<Path> result = downloader(socketPath, new CopyOnWriteArrayList<>())
                .download("v1", MAGNET, root.resolve("v1"), percent -> {});

        // Already failed on return: "the engine is not running" is a different problem from "the
        // swarm went quiet", and a caller holding a pending future cannot tell them apart.
        assertThat(result).isCompletedExceptionally();
        assertThatThrownBy(result::get)
                .hasCauseInstanceOf(TorrentDownloadException.class)
                .hasMessageContaining("not reachable");
    }
}
