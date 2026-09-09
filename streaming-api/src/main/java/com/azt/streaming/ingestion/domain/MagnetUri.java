package com.azt.streaming.ingestion.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The stable identity of a magnet link.
 *
 * <p>Two magnet URLs for the same torrent are routinely not the same string: the tracker list
 * ({@code &tr=}) and display name ({@code &dn=}) vary by where the link was copied from, and their
 * order is not fixed. Only the infohash identifies the content. Deduplicating on the raw URL would
 * therefore look correct and silently never fire.
 */
public final class MagnetUri {

    /** {@code xt=urn:btih:<40 hex | 32 base32>}, case-insensitive, anywhere in the query. */
    private static final Pattern INFO_HASH =
            Pattern.compile("xt=urn:btih:([A-Za-z0-9]{40}|[A-Za-z2-7]{32})", Pattern.CASE_INSENSITIVE);

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private MagnetUri() {}

    /**
     * A key that is equal for two links to the same torrent.
     *
     * <p>Falls back to a hash of the whole URL when no infohash can be parsed, so an unusual link
     * still gets a stable key rather than colliding with every other unparseable one.
     */
    public static String identity(String magnetUrl) {
        Matcher matcher = INFO_HASH.matcher(magnetUrl);
        if (!matcher.find()) {
            return sha256(magnetUrl);
        }
        String hash = matcher.group(1);
        // Base32 and hex spellings of the same infohash must produce the same key, or the two
        // common ways of writing a magnet would be treated as different torrents.
        return (hash.length() == 32 ? decodeBase32(hash) : hash).toLowerCase(Locale.ROOT);
    }

    private static String decodeBase32(String base32) {
        String upper = base32.toUpperCase(Locale.ROOT);
        StringBuilder bits = new StringBuilder(160);
        for (char c : upper.toCharArray()) {
            int index = BASE32_ALPHABET.indexOf(c);
            if (index < 0) {
                return sha256(base32);
            }
            bits.append("00000".substring(Integer.toBinaryString(index).length()))
                    .append(Integer.toBinaryString(index));
        }
        byte[] bytes = new byte[20];
        for (int i = 0; i < 20; i++) {
            bytes[i] = (byte) Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);
        }
        return HexFormat.of().formatHex(bytes);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
