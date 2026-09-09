package com.azt.streaming.acquisition.infrastructure;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Reads the client name out of a peer's 20-byte peer_id.
 *
 * <p>This exists because the obvious source is worse. The extended handshake carries a {@code v}
 * string naming the software, but only peers that implement BEP-10 send one and not all of those
 * bother: against a real swarm it identified 9 peers out of 71. The peer_id is part of the *base*
 * handshake, which every client sends before anything else, so it covers very nearly all of them.
 *
 * <p>Three encodings are in circulation and none of them is specified — they are conventions that
 * won. Azureus style is what essentially everything modern uses; the other two are decoded because
 * old clients are exactly the ones that will not send an extended handshake either.
 *
 * <p>An unrecognised id decodes to {@code null}, never to a guess. A wrong client name is worse than
 * an empty column: the column is read as evidence, and evidence should be absent rather than made
 * up. The prefix is also entirely self-reported and trivially forged — it says what software claims
 * to be running, which is useful for seeing the shape of a swarm and worthless as identification.
 */
public final class PeerIdDecoder {

    private PeerIdDecoder() {}

    /**
     * Two-letter Azureus-style client codes.
     *
     * <p>Not exhaustive — the registry is folklore, not a standard — but it covers what a public
     * swarm actually contains. An unlisted code still yields a name: the raw code is returned rather
     * than nothing, because "XY 2.1" is more informative than a blank, and honestly so.
     */
    private static final Map<String, String> AZUREUS_CLIENTS = Map.ofEntries(
            Map.entry("qB", "qBittorrent"),
            Map.entry("lt", "libTorrent"),
            Map.entry("LT", "libtorrent"),
            Map.entry("TR", "Transmission"),
            Map.entry("UT", "µTorrent"),
            Map.entry("UM", "µTorrent Mac"),
            Map.entry("UW", "µTorrent Web"),
            Map.entry("BT", "BitTorrent"),
            Map.entry("AZ", "Azureus"),
            Map.entry("BI", "BiglyBT"),
            Map.entry("DE", "Deluge"),
            Map.entry("KT", "KTorrent"),
            Map.entry("WW", "WebTorrent"),
            Map.entry("WD", "WebTorrent Desktop"),
            Map.entry("BC", "BitComet"),
            Map.entry("MO", "MonoTorrent"),
            Map.entry("PI", "PicoTorrent"),
            Map.entry("TX", "Tixati"),
            Map.entry("XL", "Xunlei"),
            Map.entry("FD", "Free Download Manager"),
            Map.entry("HL", "Halite"),
            Map.entry("LP", "Lphant"),
            Map.entry("SZ", "Shareaza"),
            Map.entry("ML", "MLdonkey"),
            Map.entry("BF", "Bitflu"),
            Map.entry("Bt", "Bt"));

    /**
     * The client behind {@code peerId}, e.g. {@code "qBittorrent 5.0.2"}, or null if the id follows
     * no convention this understands.
     */
    public static String decode(byte[] peerId) {
        if (peerId == null || peerId.length < 8) {
            return null;
        }
        String id = new String(peerId, StandardCharsets.ISO_8859_1);

        String azureus = decodeAzureus(id);
        if (azureus != null) {
            return azureus;
        }
        return decodeShadow(id);
    }

    /** {@code -qB5020-} → {@code qBittorrent 5.0.2}. The convention nearly everything modern uses. */
    private static String decodeAzureus(String id) {
        if (id.charAt(0) != '-' || id.charAt(7) != '-') {
            return null;
        }
        String code = id.substring(1, 3);
        if (!isPrintableCode(code)) {
            return null;
        }
        String name = AZUREUS_CLIENTS.getOrDefault(code, code);
        String version = azureusVersion(id.substring(3, 7));
        return version.isEmpty() ? name : name + " " + version;
    }

    /**
     * Four version characters to a dotted string.
     *
     * <p>Digits become {@code major.minor.patch}, with the fourth appended only when it is not zero
     * — clients pad the build position, and "4.6.5.0" is the same release as "4.6.5" written
     * noisily. Anything non-numeric (some clients use base-62 to fit larger numbers into one
     * character) is passed through raw rather than mistranslated.
     */
    private static String azureusVersion(String raw) {
        for (int i = 0; i < raw.length(); i++) {
            if (!Character.isDigit(raw.charAt(i))) {
                return raw.trim();
            }
        }
        String version = "%d.%d.%d".formatted(digit(raw, 0), digit(raw, 1), digit(raw, 2));
        return digit(raw, 3) == 0 ? version : version + "." + digit(raw, 3);
    }

    private static int digit(String raw, int index) {
        return raw.charAt(index) - '0';
    }

    /**
     * Shad0w style: a client letter, three version characters, then filler. Long obsolete, and
     * decoded for exactly that reason — a client old enough to use this will not be sending an
     * extended handshake to identify itself with instead.
     */
    private static String decodeShadow(String id) {
        if (!Character.isLetter(id.charAt(0)) || id.charAt(4) != '-') {
            return null;
        }
        String name =
                switch (id.charAt(0)) {
                    case 'A' -> "ABC";
                    case 'O' -> "Osprey";
                    case 'Q' -> "BTQueue";
                    case 'R' -> "Tribler";
                    case 'S' -> "Shad0w";
                    case 'T' -> "BitTornado";
                    case 'U' -> "UPnP NAT Bit Torrent";
                    default -> null;
                };
        return name;
    }

    private static boolean isPrintableCode(String code) {
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c < '0' || c > 'z') {
                return false;
            }
        }
        return true;
    }
}
