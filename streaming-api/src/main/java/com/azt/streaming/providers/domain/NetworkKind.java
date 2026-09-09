package com.azt.streaming.providers.domain;

/**
 * What kind of network an address sits on, as far as its operator's name suggests.
 *
 * <p>A guess, and named so that callers cannot forget it: {@link #HOSTING} means "this looks like a
 * datacenter or a VPN exit", not "this is one". Everything it knows comes from the autonomous
 * system's registered organisation, which is a marketing name, not a declaration of purpose.
 */
public enum NetworkKind {
    /** A consumer ISP, as far as anything here can tell. The default reading of an unremarkable AS. */
    RESIDENTIAL,

    /**
     * A datacenter, cloud or VPN exit — a machine somewhere, not a household.
     *
     * <p>Worth separating because it changes what a peer <em>is</em>. Studies of public swarms put
     * roughly a fifth of addresses behind this kind of network, and a peer on one is a rented
     * server or somebody else's tunnel: the address identifies the operator, and says nothing about
     * where the person using it is.
     */
    HOSTING,

    /** No network was recorded, so there is nothing to judge. */
    UNKNOWN
}
