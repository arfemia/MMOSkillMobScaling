package com.ziggfreed.mmomobscaling.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Guards the zone-threat banding (difficulty minus viewer power) and the tier lang-key convention
 * the HUD renders through (every key must exist in {@code scaling.lang}; {@code ScalingLangTest}
 * covers the file side).
 */
class ZoneTierTest {

    @Test
    void deltaBandsClassify() {
        assertEquals(ZoneTier.TRIVIAL, ZoneTier.fromDelta(-40.0), "far below the player");
        assertEquals(ZoneTier.TRIVIAL, ZoneTier.fromDelta(-15.0), "trivial boundary inclusive");
        assertEquals(ZoneTier.EASY, ZoneTier.fromDelta(-10.0));
        assertEquals(ZoneTier.EASY, ZoneTier.fromDelta(-5.0), "easy boundary inclusive");
        assertEquals(ZoneTier.FAIR, ZoneTier.fromDelta(0.0), "matched power is fair");
        assertEquals(ZoneTier.FAIR, ZoneTier.fromDelta(4.9));
        assertEquals(ZoneTier.HARD, ZoneTier.fromDelta(5.0), "hard starts at +5");
        assertEquals(ZoneTier.HARD, ZoneTier.fromDelta(14.9));
        assertEquals(ZoneTier.DEADLY, ZoneTier.fromDelta(15.0), "deadly starts at +15");
        assertEquals(ZoneTier.DEADLY, ZoneTier.fromDelta(80.0));
    }

    @Test
    void langKeysFollowTheConvention() {
        for (ZoneTier tier : ZoneTier.values()) {
            assertTrue(tier.langKey().startsWith("scaling.hud.zone.tier."),
                    tier + " key follows the scaling.hud.zone.tier.* convention");
            assertTrue(tier.colorHex().matches("#[0-9a-fA-F]{6}"),
                    tier + " carries a six-digit hex TextColor");
        }
    }
}
