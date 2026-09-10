package com.azt.streaming.providers.application;

import com.azt.streaming.acquisition.domain.PeerObservation;
import com.azt.streaming.acquisition.domain.PeerObservationSink;
import com.azt.streaming.providers.domain.PeerRole;
import com.azt.streaming.providers.domain.ProviderPeer;
import com.azt.streaming.providers.domain.ProviderPeerRepository;
import com.azt.streaming.providers.domain.ProviderSummary;
import com.azt.streaming.providers.infrastructure.GeoIpEnricher;
import com.azt.streaming.shared.storage.CatalogEntry;
import com.azt.streaming.shared.storage.VideoCatalog;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Records who served each video.
 *
 * <p>Sits between two things with incompatible ideas about concurrency. The BitTorrent library
 * reports peers from many threads, at whatever rate the swarm produces them; SQLite wants one
 * writer and a geolocation lookup is a file read. So sightings go onto a queue and a single thread
 * drains it, which keeps every download's connection handlers out of a database entirely.
 *
 * <p>The queue is bounded and drops on overflow. That is the deliberate choice: this is an
 * observation of a download, and a download must never stall or fail because the log of it fell
 * behind. Drops are counted and reported rather than passed over in silence.
 */
@Slf4j
public class ProviderPeerLog implements PeerObservationSink, DisposableBean {

    /**
     * How many entries each distribution returns.
     *
     * <p>Enough to show the shape of a swarm — which is a few operators and a long tail — without
     * turning three lists into a wall. The page prints what the cut leaves out rather than
     * truncating in silence.
     */
    private static final int TOP_SLICES = 8;

    private final ProviderPeerRepository repository;
    private final GeoIpEnricher geoIp;
    private final VideoCatalog videoCatalog;
    private final Clock clock;
    private final Duration retention;

    private final BlockingQueue<PeerObservation> queue;
    private final Thread writer;
    private final AtomicLong dropped = new AtomicLong();

    private volatile boolean running = true;

    public ProviderPeerLog(
            ProviderPeerRepository repository,
            GeoIpEnricher geoIp,
            VideoCatalog videoCatalog,
            Clock clock,
            Duration retention,
            int queueCapacity) {
        this.repository = repository;
        this.geoIp = geoIp;
        this.videoCatalog = videoCatalog;
        this.clock = clock;
        this.retention = retention;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);

        this.writer = new Thread(this::drain, "provider-peer-log");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    @Override
    public void record(PeerObservation observation) {
        if (!queue.offer(observation) && dropped.incrementAndGet() % 1000 == 1) {
            log.warn("Provider log is behind; {} peer sightings dropped so far", dropped.get());
        }
    }

    private void drain() {
        while (running) {
            try {
                PeerObservation observation = queue.take();
                persist(observation);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // One bad row must not end the writer thread and silently stop the whole log.
                log.warn("Failed to write a peer sighting", e);
            }
        }
    }

    private void persist(PeerObservation observation) {
        repository.record(new ProviderPeer(
                observation.videoId(),
                observation.infoHash(),
                observation.ipAddress(),
                observation.port(),
                observation.client(),
                geoIp.locate(observation.ipAddress()),
                PeerRole.of(observation.piecesComplete(), observation.piecesTotal()),
                observation.piecesComplete(),
                observation.piecesTotal(),
                observation.kind() == PeerObservation.Kind.CONNECTED ? 1 : 0,
                observation.bytesDownloaded() == null ? 0L : observation.bytesDownloaded(),
                observation.bytesUploaded() == null ? 0L : observation.bytesUploaded(),
                observation.connectedSeconds() == null ? 0 : observation.connectedSeconds(),
                observation.capabilities() == null ? List.of() : List.copyOf(observation.capabilities()),
                // Both are read back out of the database, never written: one is derived from the
                // network name, the other is a count across every video.
                null,
                0,
                observation.observedAt(),
                observation.observedAt()));
    }

    /** Peers known for one video, most recently seen first. */
    public List<ProviderPeer> forVideo(String videoId) {
        return repository.findByVideoId(videoId);
    }

    /** The whole log, most recently seen first. */
    public List<ProviderPeer> recent(int limit) {
        return repository.findRecent(limit);
    }

    /**
     * Everything the provenance page draws, in one pass.
     *
     * <p>The join happens here rather than in SQL because the two halves live in different places by
     * design: the counts come from the peer log, and the title, poster and continued existence of
     * the media come from the catalogue on disk, which is the source of truth for what can be played
     * (ADR-0003). Neither knows about the other, and this is the only thing that needs both.
     *
     * @param videoId narrows the map to one ingestion, or null for everything
     */
    public ProviderSummary summary(String videoId) {
        Map<String, CatalogEntry> onDisk =
                videoCatalog.list().stream().collect(Collectors.toMap(CatalogEntry::videoId, Function.identity()));

        List<ProviderSummary.VideoProvenance> videos = repository.aggregateByVideo().stream()
                .map(aggregate -> {
                    CatalogEntry entry = onDisk.get(aggregate.videoId());
                    return new ProviderSummary.VideoProvenance(
                            aggregate.videoId(),
                            entry == null ? null : entry.title(),
                            entry == null ? null : entry.readyAt(),
                            // The media is gone but the record of where it came from is not, which
                            // is the arrangement ADR-0014 asked for: peers outlive the video by
                            // three weeks. Listed, and marked, rather than quietly dropped.
                            entry != null,
                            entry != null && entry.hasPoster(),
                            aggregate.peerCount(),
                            aggregate.connectedCount(),
                            aggregate.seederCount(),
                            aggregate.bytesDownloaded(),
                            aggregate.firstSeen(),
                            aggregate.lastSeen());
                })
                .toList();

        return new ProviderSummary(
                repository.totals(),
                videos,
                repository.aggregateByPlace(videoId),
                new ProviderSummary.Distributions(
                        repository.topValuesOf(ProviderPeerRepository.Dimension.CLIENT, TOP_SLICES),
                        repository.topValuesOf(ProviderPeerRepository.Dimension.COUNTRY, TOP_SLICES),
                        repository.topValuesOf(ProviderPeerRepository.Dimension.NETWORK, TOP_SLICES)));
    }

    /**
     * Expires rows past the retention window.
     *
     * <p>Daily, and on the same principle as the media reaper: an address is personal data, and the
     * only defensible reason to still hold one is that the window has not closed yet.
     */
    @Scheduled(fixedDelay = 24, timeUnit = java.util.concurrent.TimeUnit.HOURS, initialDelay = 1)
    public void expireOldPeers() {
        int removed = repository.deleteOlderThan(clock.instant().minus(retention));
        if (removed > 0) {
            log.info("Provider log: expired {} peer row(s) past the {} window", removed, retention);
        }
    }

    @Override
    public void destroy() {
        running = false;
        writer.interrupt();
    }
}
