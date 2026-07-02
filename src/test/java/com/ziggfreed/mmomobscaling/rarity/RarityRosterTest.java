package com.ziggfreed.mmomobscaling.rarity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.util.SplitMix64;

/**
 * Determinism + band-gating tests for {@link RarityRoster}. Uses hand-built {@link Rarity} tiers (no codec)
 * so the roll logic is tested in isolation.
 */
class RarityRosterTest {

    private static Rarity rarity(String id, double weight, double minDiff) {
        return new Rarity(id, "", weight, minDiff, 1, 1, 1, 1, 1, 0, null, List.of("*"));
    }

    /** The shipped starter ladder shape (boss has weight 0 -> not rollable). */
    private static RarityRoster ladder() {
        return RarityRoster.build(List.of(
                rarity("rare", 70, 5),
                rarity("epic", 25, 25),
                rarity("legendary", 5, 50),
                rarity("boss", 0, 0)));
    }

    @Test
    void weightZeroTierIsNotRollable() {
        assertEquals(3, ladder().size(), "boss (weight 0) must be dropped from the roster");
    }

    @Test
    void deterministicForTheSameSeed() {
        RarityRoster r = ladder();
        Rarity a = r.pick(60, 1.0, new SplitMix64(12345L));
        Rarity b = r.pick(60, 1.0, new SplitMix64(12345L));
        assertEquals(id(a), id(b), "same seed -> same rarity");
    }

    @Test
    void chanceZeroAlwaysPlain() {
        RarityRoster r = ladder();
        for (long s = 0; s < 64; s++) {
            assertNull(r.pick(100, 0.0, new SplitMix64(s)), "raritySpawnChance 0 must always yield plain");
        }
    }

    @Test
    void bandGatingExcludesHigherTiers() {
        RarityRoster r = ladder();
        // At difficulty 10 only "rare" (minDiff 5) is eligible; epic (25) / legendary (50) are gated out.
        for (long s = 0; s < 256; s++) {
            Rarity picked = r.pick(10, 1.0, new SplitMix64(s));
            if (picked != null) {
                assertEquals("rare", picked.id(), "only rare eligible at difficulty 10");
            }
        }
    }

    @Test
    void higherDifficultyCanRollEpicOrLegendary() {
        RarityRoster r = ladder();
        boolean saw = false;
        for (long s = 0; s < 512 && !saw; s++) {
            Rarity picked = r.pick(60, 1.0, new SplitMix64(s));
            saw = picked != null && (picked.id().equals("epic") || picked.id().equals("legendary"));
        }
        assertTrue(saw, "epic/legendary should appear at difficulty 60");
    }

    private static String id(Rarity r) {
        return r == null ? null : r.id();
    }
}
