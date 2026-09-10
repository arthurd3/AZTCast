package com.azt.streaming.providers.infrastructure;

import com.azt.streaming.providers.domain.NetworkKind;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Guesses whether an address belongs to a datacenter or a VPN rather than to a home.
 *
 * <p>Worth doing because the swarm is full of them: a single live download here resolved peers to
 * Proton AG, M247, Datacamp, Global Layer and Clouvider — VPN exits and rented servers, not houses.
 * Research on public swarms puts that share at roughly a fifth, and it is the difference between
 * "somebody in Zurich" and "a machine in Zurich rented by somebody who could be anywhere".
 *
 * <p>Decided from the autonomous system number and the operator name that are <em>already stored</em>
 * — no request is made to anyone. That constraint is the whole reason this is a bundled list rather
 * than one of the commercial detection APIs: sending them every peer we talk to would hand a third
 * party the list of who serves us, which is precisely what reading geolocation from a local file
 * avoids.
 *
 * <p><strong>It is a heuristic and it is wrong sometimes.</strong> The name list cannot know about a
 * provider it has never met, and a residential ISP that also sells hosting will be misread. Callers
 * present it as "provável", never as fact.
 */
public final class HostingNetworks {

    private HostingNetworks() {}

    /**
     * Operators worth naming outright, because their AS names carry no useful keyword.
     *
     * <p>Kept short deliberately. An exhaustive registry of hosting ASNs is a product, maintained
     * daily by people who do nothing else; this is the handful that actually turned up in swarms
     * plus the large clouds, and the keyword pass below catches the long tail.
     */
    private static final Set<Long> HOSTING_ASNS = Set.of(
            16509L, 14618L, // Amazon
            15169L, 396982L, // Google
            8075L, // Microsoft
            16276L, // OVH
            24940L, // Hetzner
            14061L, // DigitalOcean
            63949L, 20473L, // Akamai (Linode), Vultr/Choopa
            16247L, // Datacamp — the backbone of a great many consumer VPNs
            9009L, // M247
            208172L, 209103L, // Proton
            60068L, // Datacamp Limited
            13335L, // Cloudflare
            31898L, // Oracle
            51167L, // Contabo
            12876L, // Scaleway
            60781L, // Leaseweb
            197540L, // netcup
            8100L, // QuadraNet
            53667L, // FranTech / BuyVM
            42708L, // GleSYS
            62240L, // Clouvider
            206804L, // EstNOC
            49453L, // Global Layer
            31173L // 31173 Services — Mullvad
            );

    /**
     * Substrings that give an operator away, matched against the lowercased AS name.
     *
     * <p>Chosen to be words that a consumer ISP would not put in its own name. Deliberately absent:
     * "telecom", "communications", "net", "broadband" and the like, which describe most residential
     * carriers on earth and would classify the whole internet as a datacenter.
     */
    private static final List<String> HOSTING_KEYWORDS = List.of(
            "hosting",
            "host ",
            "webhost",
            "datacenter",
            "data center",
            "datacentre",
            "colocation",
            "colocrossing",
            "cloud",
            "vpn",
            "vps",
            "dedicated server",
            "server",
            "amazon",
            "google",
            "microsoft",
            "azure",
            "digitalocean",
            "linode",
            "vultr",
            "choopa",
            "hetzner",
            "ovh",
            "leaseweb",
            "contabo",
            "scaleway",
            "netcup",
            "ionos",
            "rackspace",
            "equinix",
            "zenlayer",
            "psychz",
            "dedipath",
            "hostwinds",
            "kamatera",
            "alibaba",
            "tencent",
            "oracle",
            "cloudflare",
            "datacamp",
            "m247",
            "proton",
            "mullvad",
            "nordvpn",
            "tefincom",
            "surfshark",
            "expressvpn",
            "cyberghost",
            "windscribe",
            "torguard",
            "privado",
            "private internet",
            "clouvider",
            "estnoc",
            "global layer",
            "glesys",
            "worldstream",
            "serverius",
            "hosteurope",
            "digital ocean");

    /** What kind of network {@code organisation} (AS {@code asn}) looks like. */
    public static NetworkKind classify(Long asn, String organisation) {
        if (asn == null && (organisation == null || organisation.isBlank())) {
            return NetworkKind.UNKNOWN;
        }
        if (asn != null && HOSTING_ASNS.contains(asn)) {
            return NetworkKind.HOSTING;
        }
        if (organisation == null || organisation.isBlank()) {
            // An AS number with no name attached: known to exist, nothing to judge it by.
            return NetworkKind.UNKNOWN;
        }
        String name = organisation.toLowerCase(Locale.ROOT);
        return HOSTING_KEYWORDS.stream().anyMatch(name::contains) ? NetworkKind.HOSTING : NetworkKind.RESIDENTIAL;
    }
}
