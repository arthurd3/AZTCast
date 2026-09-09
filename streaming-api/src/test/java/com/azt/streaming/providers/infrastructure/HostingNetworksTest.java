package com.azt.streaming.providers.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.azt.streaming.providers.domain.NetworkKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Telling a rented machine from a household, by the name on its network.
 *
 * <p>Every operator named here was seen in an actual swarm during development, which is the only
 * reason any of them is in the list — it is a record of what turns up, not an attempt at a registry.
 *
 * <p>The test that matters most is the last one. This is a heuristic, so the interesting question is
 * not how often it is right but what it does when it has no idea, and the answer has to be "says so"
 * rather than "guesses HOSTING because the name looked technical".
 */
class HostingNetworksTest {

    @Test
    @DisplayName("VPN exits are hosting, however residential the country looks")
    void recognisesVpnOperators() {
        // All three served real downloads here. A peer on one of these is a tunnel endpoint: the
        // city attached to it is the exit's city, and says nothing about where anyone is sitting.
        assertThat(HostingNetworks.classify(209103L, "Proton AG")).isEqualTo(NetworkKind.HOSTING);
        assertThat(HostingNetworks.classify(9009L, "M247 Europe SRL")).isEqualTo(NetworkKind.HOSTING);
        assertThat(HostingNetworks.classify(60068L, "Datacamp Limited")).isEqualTo(NetworkKind.HOSTING);
        assertThat(HostingNetworks.classify(31173L, "31173 Services AB")).isEqualTo(NetworkKind.HOSTING);
    }

    @Test
    void recognisesClouds() {
        assertThat(HostingNetworks.classify(16509L, "Amazon.com, Inc.")).isEqualTo(NetworkKind.HOSTING);
        assertThat(HostingNetworks.classify(24940L, "Hetzner Online GmbH")).isEqualTo(NetworkKind.HOSTING);
        assertThat(HostingNetworks.classify(31898L, "Oracle Corporation")).isEqualTo(NetworkKind.HOSTING);
        assertThat(HostingNetworks.classify(49453L, "Global Layer B.V.")).isEqualTo(NetworkKind.HOSTING);
    }

    @Test
    @DisplayName("an unlisted operator is still caught by what it calls itself")
    void matchesOnKeywordsWhenTheAsnIsUnknown() {
        // The ASN list will always be incomplete; the name usually gives it away anyway.
        assertThat(HostingNetworks.classify(999999L, "Some Cloud Hosting Ltd")).isEqualTo(NetworkKind.HOSTING);
        assertThat(HostingNetworks.classify(999999L, "Acme VPS & Dedicated Server")).isEqualTo(NetworkKind.HOSTING);
    }

    @Test
    @DisplayName("consumer ISPs are not swept up by generic networking words")
    void leavesResidentialCarriersAlone() {
        // These are the false positives that would matter: every one of these is somebody's home
        // connection, and all of them contain words a careless keyword list would match.
        assertThat(HostingNetworks.classify(28573L, "Claro NXT Telecomunicacoes Ltda"))
                .isEqualTo(NetworkKind.RESIDENTIAL);
        assertThat(HostingNetworks.classify(7018L, "AT&T Enterprises, LLC")).isEqualTo(NetworkKind.RESIDENTIAL);
        assertThat(HostingNetworks.classify(24400L, "China Mobile Communications Group Co., Ltd"))
                .isEqualTo(NetworkKind.RESIDENTIAL);
        assertThat(HostingNetworks.classify(1403L, "EBOX")).isEqualTo(NetworkKind.RESIDENTIAL);
        assertThat(HostingNetworks.classify(28258L, "VERO S.A")).isEqualTo(NetworkKind.RESIDENTIAL);
    }

    @Test
    @DisplayName("nothing known means UNKNOWN, never a guess")
    void refusesToJudgeWithoutEvidence() {
        assertThat(HostingNetworks.classify(null, null)).isEqualTo(NetworkKind.UNKNOWN);
        assertThat(HostingNetworks.classify(null, "  ")).isEqualTo(NetworkKind.UNKNOWN);
        // An AS number with no name: the peer exists on some network, and that is all we can say.
        assertThat(HostingNetworks.classify(64512L, null)).isEqualTo(NetworkKind.UNKNOWN);
    }
}
