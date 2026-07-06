package com.ziggfreed.mmomobscaling.rarity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.util.SplitMix64;

/**
 * Determinism + band-gating tests for {@link RarityRoster}. Uses hand-built {@link Rarity} tiers (no codec)
 * so the roll logic is tested in isolation.
 */
class RarityRosterTest {

    /** No family gating: every tier eligible (the pre-feature roll shape). */
    private static final Predicate<Rarity> ANY = r -> true;

    private static Rarity rarity(String id, double weight, double minDiff) {
        return new Rarity(id, "", weight, minDiff, 1, 1, 1, 1, 1, 0, null, null, List.of("*"));
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
        Rarity a = r.pick(60, 1.0, new SplitMix64(12345L), ANY);
        Rarity b = r.pick(60, 1.0, new SplitMix64(12345L), ANY);
        assertEquals(id(a), id(b), "same seed -> same rarity");
    }

    @Test
    void chanceZeroAlwaysPlain() {
        RarityRoster r = ladder();
        for (long s = 0; s < 64; s++) {
            assertNull(r.pick(100, 0.0, new SplitMix64(s), ANY), "raritySpawnChance 0 must always yield plain");
        }
    }

    @Test
    void bandGatingExcludesHigherTiers() {
        RarityRoster r = ladder();
        // At difficulty 10 only "rare" (minDiff 5) is eligible; epic (25) / legendary (50) are gated out.
        for (long s = 0; s < 256; s++) {
            Rarity picked = r.pick(10, 1.0, new SplitMix64(s), ANY);
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
            Rarity picked = r.pick(60, 1.0, new SplitMix64(s), ANY);
            saw = picked != null && (picked.id().equals("epic") || picked.id().equals("legendary"));
        }
        assertTrue(saw, "epic/legendary should appear at difficulty 60");
    }

    @Test
    void familyPredicateNarrowsEligibleSet() {
        RarityRoster r = ladder();
        // A family gate that only admits "epic": at difficulty 60 the roll may return epic or plain (null),
        // NEVER rare/legendary, even though both are otherwise band-eligible.
        Predicate<Rarity> onlyEpic = t -> t.id().equals("epic");
        for (long s = 0; s < 512; s++) {
            Rarity picked = r.pick(60, 1.0, new SplitMix64(s), onlyEpic);
            if (picked != null) {
                assertEquals("epic", picked.id(), "family gate must exclude non-epic tiers");
            }
        }
    }

    @Test
    void emptyEligibleSetYieldsPlain() {
        RarityRoster r = ladder();
        // A family gate that admits nothing filters every tier out -> always plain, even at chance 1.0.
        for (long s = 0; s < 128; s++) {
            assertNull(r.pick(100, 1.0, new SplitMix64(s), t -> false),
                    "an all-filtered pool must yield plain (null), never throw");
        }
    }

    @Test
    void familyPredicateDoesNotChangeDeterminism() {
        RarityRoster r = ladder();
        // The predicate consumes no RNG: an all-pass gate must reproduce the un-gated pick bit-for-bit.
        for (long s = 0; s < 128; s++) {
            Rarity gated = r.pick(60, 1.0, new SplitMix64(s), ANY);
            Rarity plain = r.pick(60, 1.0, new SplitMix64(s), t -> true);
            assertEquals(id(gated), id(plain), "an all-pass gate must not perturb the roll");
        }
    }

    private static String id(Rarity r) {
        return r == null ? null : r.id();
    }
}
