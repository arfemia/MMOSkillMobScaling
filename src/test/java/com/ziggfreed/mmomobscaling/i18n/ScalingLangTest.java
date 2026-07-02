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

import org.junit.jupiter.api.Test;

import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.rarity.Rarity;

/**
 * Enforces the two lang invariants for this mod's {@code scaling.lang}: affix {@code .desc} values are
 * QUALITATIVE (no digits - magnitudes live in the EntityEffect assets) and there are no em-dashes. Also
 * covers {@link MobScalingTextUtil}'s explicit-else-convention key resolution (the C-loc1 fix).
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

    @Test
    void noEmDashesInLang() throws Exception {
        for (Map.Entry<String, String> e : loadLang().entrySet()) {
            assertFalse(e.getValue().contains("—"), e.getKey() + " contains an em-dash");
        }
    }

    @Test
    void textUtilFallsBackToConventionKey() {
        Rarity noKey = rarity("rare", "");
        assertEquals("scaling.rarity.rare.name", MobScalingTextUtil.rarityNameKey(noKey), "convention fallback");
        Rarity explicit = rarity("rare", "custom.rarity.key");
        assertEquals("custom.rarity.key", MobScalingTextUtil.rarityNameKey(explicit), "explicit key wins");

        Affix affix = new Affix("armored", "", "", null, 1, 1, List.of("*"), 0, 0, 0, 0, Affix.KIND_STAT, null, true);
        assertEquals("scaling.affix.armored.name", MobScalingTextUtil.affixNameKey(affix));
        assertEquals("scaling.affix.armored.desc", MobScalingTextUtil.affixDescKey(affix));
    }

    private static Rarity rarity(String id, String nameKey) {
        return new Rarity(id, nameKey, 1, 1, 1, 1, 1, 1, 1, 0, null, null, List.of("*"));
    }

    private static Map<String, String> loadLang() throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (InputStream in = ScalingLangTest.class.getResourceAsStream("/Server/Languages/en-US/scaling.lang")) {
            assertNotNull(in, "scaling.lang must be on the classpath");
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
