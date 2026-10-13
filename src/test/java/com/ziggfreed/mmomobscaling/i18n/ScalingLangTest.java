package com.ziggfreed.mmomobscaling.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.rarity.Rarity;

/**
 * Enforces the two lang invariants for this mod's {@code mmomobscaling.lang}: affix {@code .desc} values are
 * QUALITATIVE (no digits - magnitudes live in the EntityEffect assets) and there are no em-dashes; and that
 * each translated key keeps en-US's placeholders and has a value. Also covers {@link MobScalingTextUtil}'s
 * explicit-else-convention key resolution (the C-loc1 fix).
 */
class ScalingLangTest {

    @Test
    void affixDescriptionsHaveNoDigits() throws Exception {
        Map<String, String> lang = loadLang();
        long descCount = 0;
        for (Map.Entry<String, String> e : lang.entrySet()) {
            if (e.getKey().startsWith("affix.") && e.getKey().endsWith(".desc")) {
                descCount++;
                assertFalse(e.getValue().matches(".*\\d.*"),
                        e.getKey() + " must be qualitative (no digits): " + e.getValue());
            }
        }
        assertTrue(descCount >= 5, "expected the 5 shipped affix .desc keys, found " + descCount);
    }

    // The em-dash literal below is DELIBERATE: this test is the guard that keeps the lang files free of
    // em-dashes, so it must name the character it forbids. A repo-wide em-dash sweep leaves this one alone.
    @Test
    void noEmDashesInLang() throws Exception {
        for (Map.Entry<String, String> e : loadLang().entrySet()) {
            assertFalse(e.getValue().contains("—"), e.getKey() + " contains an em-dash");
        }
    }

    /**
     * Each key a locale file carries keeps the placeholders en-US gives it, has a value (the engine's
     * parser skips {@code key = } as malformed, so the key would render raw on screen) and holds no
     * em-dash. A string the page binds a param into must keep that param in every language or the number
     * silently vanishes there. Missing and extra keys are not checked here: the MMO's
     * {@code LangKeyParityTest} reports them across the family and fails on them at the release gate
     * ({@code -PlangGate}), so a key new in en-US waits for the release's lang wave without failing this
     * build.
     */
    @Test
    void everyTranslatedKeyKeepsItsPlaceholdersAndHasAValue() throws Exception {
        Map<String, String> en = loadLang("en-US");
        for (String locale : LOCALES) {
            for (Map.Entry<String, String> e : loadLang(locale).entrySet()) {
                String key = e.getKey();
                String value = e.getValue();
                assertFalse(value.isEmpty(), locale + " has an empty value for " + key);
                // U+2014 by code point: the one literal em-dash this file may carry is the commented one above.
                assertFalse(value.indexOf(0x2014) >= 0, locale + " " + key + " contains an em-dash");
                String english = en.get(key);
                if (english != null) {
                    assertEquals(placeholders(english), placeholders(value),
                            locale + " changes the placeholders of " + key);
                }
            }
        }
    }

    private static final List<String> LOCALES = List.of(
            "de-DE", "es-ES", "fr-FR", "hu-HU", "it-IT", "pt-BR", "ru-RU", "tr-TR");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^}]+}");

    private static Set<String> placeholders(String value) {
        Set<String> out = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(value);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    @Test
    void textUtilFallsBackToConventionKey() {
        Rarity noKey = rarity("rare", "");
        assertEquals("mmomobscaling.rarity.rare.name", MobScalingTextUtil.rarityNameKey(noKey), "convention fallback");
        Rarity explicit = rarity("rare", "custom.rarity.key");
        assertEquals("custom.rarity.key", MobScalingTextUtil.rarityNameKey(explicit), "explicit key wins");

        Affix affix = new Affix("armored", "", "", null, 1, 1, List.of("*"), 0, 0, 0, 0, Affix.KIND_STAT, null, true);
        assertEquals("mmomobscaling.affix.armored.name", MobScalingTextUtil.affixNameKey(affix));
        assertEquals("mmomobscaling.affix.armored.desc", MobScalingTextUtil.affixDescKey(affix));
    }

    private static Rarity rarity(String id, String nameKey) {
        return new Rarity(id, nameKey, 1, 1, 1, 1, 1, 0, null, List.of("*"));
    }

    private static Map<String, String> loadLang() throws Exception {
        return loadLang("en-US");
    }

    private static Map<String, String> loadLang(String locale) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (InputStream in = ScalingLangTest.class.getResourceAsStream(
                "/Server/Languages/" + locale + "/mmomobscaling.lang")) {
            assertNotNull(in, locale + " mmomobscaling.lang must be on the classpath");
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String s = line.strip();
                if (s.isEmpty() || s.startsWith("#")) {
                    continue;
                }
                int eq = s.indexOf('=');
                if (eq > 0) {
                    out.put(s.substring(0, eq).strip(), s.substring(eq + 1).strip());
                }
            }
        }
        return out;
    }
}
