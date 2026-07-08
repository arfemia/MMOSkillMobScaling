package com.ziggfreed.mmomobscaling.variant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.util.SplitMix64;
import com.ziggfreed.mmomobscaling.family.FamilyFilter;

/** Determinism + chance-partition + family/difficulty/rarity gating tests for {@link VariantRoster}. */
class VariantRosterTest {

    /** No family gating: every variant eligible. */
    private static final Predicate<Variant> ANY = v -> true;

    /** A base rarity id that any {@code AllowedRarities:["*"]} variant accepts. */
    private static final String ANY_RARITY = "epic";

    private static Variant variant(String id, double chance, double minDiff) {
        return new Variant(id, "", chance, minDiff, 1, 1, 1, 1, 1, 0, List.of("*"));
    }

    /** A variant with an explicit requires-rarity gate. */
    private static Variant gatedVariant(String id, double chance, List<String> allowedRarities) {
        return new Variant(id, "", chance, 0, 1, 1, 1, 1, 1, 0, List.of("*"),
                allowedRarities, null, null, "", FamilyFilter.ALLOW_ALL);
    }

    @Test
    void chanceZeroIsNotRollable() {
        VariantRoster r = VariantRoster.build(List.of(variant("horrific", 0.0, 0)));
        assertEquals(0, r.size(), "a Chance-0 variant is dropped from the roster");
        assertNull(r.pick(100, ANY_RARITY, new SplitMix64(1L), ANY), "empty roster -> no variant");
    }

    @Test
    void deterministicForTheSameSeed() {
        VariantRoster r = VariantRoster.build(List.of(variant("horrific", 0.5, 0)));
        Variant a = r.pick(50, ANY_RARITY, new SplitMix64(999L), ANY);
        Variant b = r.pick(50, ANY_RARITY, new SplitMix64(999L), ANY);
        assertEquals(id(a), id(b), "same seed -> same variant outcome");
    }

    @Test
    void chancePartitionApproximatesTheAuthoredRate() {
        // A single variant at Chance 0.25: about a quarter of seeds should roll it, the rest none.
        VariantRoster r = VariantRoster.build(List.of(variant("horrific", 0.25, 0)));
        int hits = 0;
        int n = 4000;
        for (long s = 0; s < n; s++) {
            if (r.pick(50, ANY_RARITY, new SplitMix64(s), ANY) != null) {
                hits++;
            }
        }
        double rate = hits / (double) n;
        assertTrue(rate > 0.20 && rate < 0.30, "rate ~0.25, was " + rate);
    }

    @Test
    void difficultyGateExcludesBelowBand() {
        VariantRoster r = VariantRoster.build(List.of(variant("horrific", 1.0, 40)));
        for (long s = 0; s < 128; s++) {
            assertNull(r.pick(10, ANY_RARITY, new SplitMix64(s), ANY), "below MinDifficulty 40 -> never rolls");
        }
        boolean saw = false;
        for (long s = 0; s < 8 && !saw; s++) {
            saw = r.pick(50, ANY_RARITY, new SplitMix64(s), ANY) != null;
        }
        assertTrue(saw, "at difficulty 50 the variant is reachable");
    }

    @Test
    void familyPredicateGatesEligibility() {
        VariantRoster r = VariantRoster.build(List.of(variant("horrific", 1.0, 0)));
        // A family gate that admits nothing -> never rolls, regardless of the draw.
        for (long s = 0; s < 128; s++) {
            assertNull(r.pick(50, ANY_RARITY, new SplitMix64(s), v -> false), "family-ineligible -> no variant");
        }
    }

    @Test
    void requiresRarityGate() {
        // A variant that only overlays epic/legendary: never on a plain ("") or rare mob, always reachable on epic.
        VariantRoster r = VariantRoster.build(List.of(gatedVariant("horrific", 1.0, List.of("epic", "legendary"))));
        for (long s = 0; s < 128; s++) {
            assertNull(r.pick(50, "", new SplitMix64(s), ANY), "plain base -> gated variant never rolls");
            assertNull(r.pick(50, "rare", new SplitMix64(s), ANY), "rare base -> gated variant never rolls");
        }
        boolean saw = false;
        for (long s = 0; s < 8 && !saw; s++) {
            saw = r.pick(50, "epic", new SplitMix64(s), ANY) != null;
        }
        assertTrue(saw, "epic base -> the gated variant is reachable");
    }

    @Test
    void wildcardRarityGateAllowsPlainBase() {
        // The default ["*"] gate overlays even a plain (no-rarity) mob.
        VariantRoster r = VariantRoster.build(List.of(variant("horrific", 1.0, 0)));
        boolean saw = false;
        for (long s = 0; s < 8 && !saw; s++) {
            saw = r.pick(50, "", new SplitMix64(s), ANY) != null;
        }
        assertTrue(saw, "a ['*'] variant overlays a plain-base mob");
    }

    @Test
    void worldChanceMultiplierScalesAndZeroDisables() {
        VariantRoster r = VariantRoster.build(List.of(variant("horrific", 0.25, 0)));
        // Scale 0 = the world rolled variants out entirely (Pool.Variants.ChanceMultiplier 0).
        for (long s = 0; s < 128; s++) {
            assertNull(r.pick(50, ANY_RARITY, 0.0, new SplitMix64(s), ANY), "scale 0 never rolls a variant");
        }
        // Scale 2 roughly doubles the hit rate vs scale 1 (same seed stream, wider slice).
        int base = 0;
        int scaled = 0;
        int n = 4000;
        for (long s = 0; s < n; s++) {
            if (r.pick(50, ANY_RARITY, 1.0, new SplitMix64(s), ANY) != null) base++;
            if (r.pick(50, ANY_RARITY, 2.0, new SplitMix64(s), ANY) != null) scaled++;
        }
        assertTrue(scaled > base * 3 / 2, "a 2.0 multiplier meaningfully raises the rate: "
                + base + " -> " + scaled);
        assertTrue(base > 0, "the unscaled rate is nonzero");
    }

    @Test
    void worldGatePredicateBlocksADeniedVariant() {
        // The per-world Pool.Variants deny gate ANDs into the eligibility predicate at the call site.
        VariantRoster r = VariantRoster.build(List.of(variant("horrific", 1.0, 0)));
        Predicate<Variant> denyHorrific = v -> !"horrific".equals(v.id());
        for (long s = 0; s < 128; s++) {
            assertNull(r.pick(50, ANY_RARITY, 1.0, new SplitMix64(s), denyHorrific),
                    "a world-denied variant never rolls");
        }
    }

    private static String id(Variant v) {
        return v == null ? null : v.id();
    }
}
