package com.azt.streaming.acquisition.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EgressInterfaceTest {

    @Test
    @DisplayName("never returns a container bridge when the host has a real route")
    void picksARoutableAddress() throws Exception {
        Optional<EgressInterface.Choice> choice = EgressInterface.detect();

        // A host with no network at all is a legitimate CI shape; there is nothing to assert there.
        if (choice.isEmpty()) {
            return;
        }
        InetAddress address = choice.get().address();
        assertThat(address.isLoopbackAddress()).isFalse();
        assertThat(address.isAnyLocalAddress()).isFalse();

        // The defect, stated as an assertion. bt's own scan takes the first non-loopback IPv4 the
        // JVM enumerates, and on a machine with Docker installed that is a bridge — sixteen of them
        // came ahead of the physical NIC on the machine this was written on.
        String owner = EgressInterface.interfaceNameOf(address);
        assertThat(EgressInterface.looksVirtual(owner))
                .as("bound to %s (%s), which is a virtual interface", address.getHostAddress(), owner)
                .isFalse();
    }

    @Test
    @DisplayName("recognises the interface names that are never a route out")
    void classifiesVirtualInterfaces() {
        assertThat(EgressInterface.looksVirtual("docker0")).isTrue();
        assertThat(EgressInterface.looksVirtual("br-5ff9e15ccd59")).isTrue();
        assertThat(EgressInterface.looksVirtual("veth1a2b3c4")).isTrue();
        assertThat(EgressInterface.looksVirtual("virbr0")).isTrue();
        assertThat(EgressInterface.looksVirtual("podman1")).isTrue();

        assertThat(EgressInterface.looksVirtual("enp4s0")).isFalse();
        assertThat(EgressInterface.looksVirtual("eth0")).isFalse();
        assertThat(EgressInterface.looksVirtual("wlan0")).isFalse();
        // A VPN tunnel is exactly the interface an operator most wants to bind to.
        assertThat(EgressInterface.looksVirtual("tun0")).isFalse();
        assertThat(EgressInterface.looksVirtual("wg0")).isFalse();
        assertThat(EgressInterface.looksVirtual(null)).isFalse();
    }

    @Test
    @DisplayName("the chosen address really belongs to an interface on this host")
    void reportsTheOwningInterface() throws Exception {
        Optional<EgressInterface.Choice> choice = EgressInterface.detect();
        if (choice.isEmpty()) {
            return;
        }
        String owner = EgressInterface.interfaceNameOf(choice.get().address());

        assertThat(Collections.list(NetworkInterface.getNetworkInterfaces()))
                .extracting(NetworkInterface::getName)
                .contains(owner);
        assertThat(choice.get().reason()).isNotBlank();
    }
}
