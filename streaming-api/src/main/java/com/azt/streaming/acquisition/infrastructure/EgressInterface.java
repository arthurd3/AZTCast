package com.azt.streaming.acquisition.infrastructure;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Finds the address this host actually reaches the internet from.
 *
 * <p>The library's own answer is the first non-loopback IPv4 address the JVM enumerates, with no
 * scoring, no {@code isUp()} check and no route lookup. On any machine with Docker installed that is
 * a bridge: this one enumerates sixteen of them before the physical NIC, so every run bound to
 * {@code 172.20.0.1} and logged it as if it were a considered choice. It still downloads — outbound
 * connections are NATed back onto the real interface — but the listening socket is on an address no
 * peer on the internet can route to, so the client is inbound-dead and every swarm it joins is one
 * it can only take from.
 *
 * <p>ADR-0015 already names this: "the local bind the library chooses when nothing is configured is
 * whatever interface it finds first, which on a developer machine with Docker installed is
 * frequently a bridge address rather than the real egress. It works, but it is not a considered
 * choice." This is the considered choice.
 */
@Slf4j
final class EgressInterface {

    /**
     * TEST-NET-3 (RFC 5737) and the discard port.
     *
     * <p>Reserved for documentation and guaranteed to be routed by nobody, which is exactly what is
     * wanted: connecting a UDP socket sends no packet at all. It asks the kernel to resolve a route
     * and bind a source address, and then the socket reports which one it picked.
     */
    private static final String ROUTE_PROBE_HOST = "203.0.113.1";

    private static final int DISCARD_PORT = 9;

    /**
     * Interface name prefixes that are never a host's route to the internet.
     *
     * <p>{@link NetworkInterface#isVirtual()} does not cover these — it reports sub-interfaces like
     * {@code eth0:1} and says nothing about a bridge — so the names are the only signal available.
     */
    private static final List<String> VIRTUAL_PREFIXES =
            List.of("docker", "br-", "virbr", "veth", "vmnet", "cni", "flannel", "podman", "kube");

    private EgressInterface() {}

    /**
     * The address a connection to the internet would leave from.
     *
     * @return the address and a sentence explaining how it was chosen, or empty when neither
     *     strategy finds anything — in which case the library's own guess stands, which is still
     *     what happened before any of this existed
     */
    static Optional<Choice> detect() {
        return fromDefaultRoute().or(EgressInterface::firstPhysicalInterface);
    }

    private static Optional<Choice> fromDefaultRoute() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName(ROUTE_PROBE_HOST), DISCARD_PORT);
            InetAddress local = socket.getLocalAddress();
            if (local == null || local.isAnyLocalAddress() || local.isLoopbackAddress()) {
                return Optional.empty();
            }
            return Optional.of(new Choice(local, "the source address of this host's default route"));
        } catch (SocketException | UnknownHostException e) {
            log.debug("Could not resolve the default route's source address", e);
            return Optional.empty();
        }
    }

    /**
     * The first interface that is up, not loopback, and not obviously a container bridge.
     *
     * <p>Only reached on a host with no default route — a machine whose tunnel has not come up yet,
     * typically. Weaker than the route lookup and still far better than "whatever came first".
     */
    private static Optional<Choice> firstPhysicalInterface() {
        try {
            for (NetworkInterface candidate : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!isUsable(candidate)) {
                    continue;
                }
                for (InetAddress address : Collections.list(candidate.getInetAddresses())) {
                    if (address.getAddress().length == 4 && !address.isLoopbackAddress()) {
                        return Optional.of(new Choice(address, "the first non-virtual interface that is up"));
                    }
                }
            }
        } catch (SocketException e) {
            log.debug("Could not enumerate network interfaces", e);
        }
        return Optional.empty();
    }

    private static boolean isUsable(NetworkInterface candidate) {
        try {
            if (!candidate.isUp() || candidate.isLoopback()) {
                return false;
            }
        } catch (SocketException e) {
            return false;
        }
        String name = candidate.getName().toLowerCase(Locale.ROOT);
        return VIRTUAL_PREFIXES.stream().noneMatch(name::startsWith);
    }

    /** Whether a name looks like a container or hypervisor bridge rather than a route out. */
    static boolean looksVirtual(String interfaceName) {
        String name = interfaceName == null ? "" : interfaceName.toLowerCase(Locale.ROOT);
        return VIRTUAL_PREFIXES.stream().anyMatch(name::startsWith);
    }

    /** The name of the interface carrying {@code address}, for the log line. */
    static String interfaceNameOf(InetAddress address) {
        try {
            NetworkInterface owner = NetworkInterface.getByInetAddress(address);
            return owner == null ? "unknown" : owner.getName();
        } catch (SocketException e) {
            return "unknown";
        }
    }

    /**
     * @param address the address to bind to
     * @param reason how it was chosen, for the startup log
     */
    record Choice(InetAddress address, String reason) {}
}
