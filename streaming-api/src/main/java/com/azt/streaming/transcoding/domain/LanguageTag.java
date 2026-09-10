package com.azt.streaming.transcoding.domain;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns the language codes a container carries into the ones a playlist has to advertise.
 *
 * <p>Matroska tags streams with ISO 639-2 ({@code eng}, {@code por}, {@code spa}); the HLS
 * {@code LANGUAGE} attribute is RFC 5646, which for these is the two-letter ISO 639-1 form
 * ({@code en}, {@code pt}, {@code es}). Players match a viewer's preferred language against that
 * attribute, so getting it wrong does not break playback — it silently stops the right subtitle
 * track from ever being auto-selected, which is worse, because nothing reports it.
 *
 * <p>The table is derived from the JDK's own ISO data rather than typed out, so it covers every
 * language the platform knows and cannot drift.
 */
public final class LanguageTag {

    private static final Map<String, String> ISO3_TO_ISO1 = buildIso3Index();

    private LanguageTag() {}

    /**
     * The RFC 5646 tag for a container language code, or {@code und} when there is nothing usable.
     *
     * <p>{@code und} is the registered "undetermined" tag, and saying it explicitly is better than
     * omitting the attribute: a rendition with no LANGUAGE is one a player cannot reason about at
     * all, while {@code und} at least tells it the track exists and is not a match for anything.
     */
    public static String bcp47(String containerCode) {
        if (containerCode == null || containerCode.isBlank()) {
            return "und";
        }
        String code = containerCode.trim().toLowerCase(Locale.ROOT);
        if (code.length() == 2) {
            return code;
        }
        return ISO3_TO_ISO1.getOrDefault(code, code);
    }

    /** A human label for the rendition's {@code NAME}, e.g. {@code English}. Falls back to the code. */
    public static String displayName(String containerCode) {
        String tag = bcp47(containerCode);
        if ("und".equals(tag)) {
            return "Undetermined";
        }
        String name = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH);
        return name.isBlank() ? tag : name;
    }

    private static Map<String, String> buildIso3Index() {
        Map<String, String> index = new HashMap<>();
        for (String iso1 : Locale.getISOLanguages()) {
            Locale locale = Locale.forLanguageTag(iso1);
            String iso3 = locale.getISO3Language();
            if (!iso3.isEmpty()) {
                index.putIfAbsent(iso3, iso1);
            }
        }
        // Matroska writers use the bibliographic codes about as often as the terminological ones,
        // and the JDK only indexes one of each pair. Without these, a German or French track loses
        // its language on the way into the playlist.
        index.putIfAbsent("ger", "de");
        index.putIfAbsent("fre", "fr");
        index.putIfAbsent("dut", "nl");
        index.putIfAbsent("gre", "el");
        index.putIfAbsent("chi", "zh");
        index.putIfAbsent("cze", "cs");
        index.putIfAbsent("ice", "is");
        index.putIfAbsent("per", "fa");
        index.putIfAbsent("rum", "ro");
        index.putIfAbsent("slo", "sk");
        index.putIfAbsent("wel", "cy");
        index.putIfAbsent("arm", "hy");
        index.putIfAbsent("baq", "eu");
        index.putIfAbsent("bur", "my");
        index.putIfAbsent("geo", "ka");
        index.putIfAbsent("mac", "mk");
        index.putIfAbsent("mao", "mi");
        index.putIfAbsent("may", "ms");
        index.putIfAbsent("tib", "bo");
        return Map.copyOf(index);
    }
}
