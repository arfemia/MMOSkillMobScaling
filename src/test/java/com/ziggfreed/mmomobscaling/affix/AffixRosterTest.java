package com.ziggfreed.mmomobscaling.affix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.variant.Variant;
import com.ziggfreed.common.util.SplitMix64;

/** Slot-count, resistance-uniqueness, no-duplicate, gating + determinism tests for {@link AffixRoster}. */
class AffixRosterTest {

    private static Affix affix(String id, double weight, double minDiff, List<String> allowedRarities, boolean resist) {
        return new Affix(id, "", "", null, weight, minDiff, allowedRarities, 0, 0, 0, 0, Affix.KIND_STAT, null, resist);
    }

    /** An affix gated to VARIANTS only (empty AllowedRarities = no rarity may grant it). */
    private static Affix variantAffix(String id, List<String> allowedVariants) {
        return new Affix(id, "", "", null, 5, 5, List.of(), allowedVariants, 0, 0, 0, 0,
                Affix.KIND_STAT, null, false, null, null);
    }

    private static Rarity legendary() {
        return new Rarity("legendary", "", 5, 50, 1, 1, 1, 1, 1, 3, null, null, List.of("*"));
    }

    /** A pool with TWO resistance-bearing affixes so the one-resistance rule is exercised. */
    private static AffixRoster pool() {
        return AffixRoster.build(List.of(
                affix("armored", 3, 5, List.of("*"), true),
                affix("plated", 3, 5, List.of("*"), true),
                affix("stalwart", 3, 5, List.of("*"), false),
                affix("swift", 2, 5, List.of("*"), false)));
    }

    @Test
    void respectsSlotCount() {
        List<Affix> picked = pool().pick(60, legendary(), 2, new SplitMix64(1L));
        assertTrue(picked.size() <= 2, "never exceeds the slot count");
    }

    @Test
    void atMostOneResistanceAffix() {
        AffixRoster p = pool();
        for (long s = 0; s < 400; s++) {
            long resist = p.pick(60, legendary(), 4, new SplitMix64(s)).stream().filter(Affix::resistanceBearing).count();
            assertTrue(resist <= 1, "at most one resistance-bearing affix per mob");
        }
    }

    @Test
    void noDuplicateAffixes() {
        for (long s = 0; s < 400; s++) {
            List<Affix> picked = pool().pick(60, legendary(), 4, new SplitMix64(s));
            long distinct = picked.stream().map(Affix::id).distinct().count();
            assertEquals(picked.size(), distinct, "no duplicate affix in a single roll");
        }
    }

    @Test
    void deterministicForTheSameSeed() {
        List<String> a = pool().pick(60, legendary(), 3, new SplitMix64(999L)).stream().map(Affix::id).toList();
        List<String> b = pool().pick(60, legendary(), 3, new SplitMix64(999L)).stream().map(Affix::id).toList();
        assertEquals(a, b, "same seed -> same affix set (in order)");
    }

    @Test
    void rarityGatingBlocksDisallowedAffix() {
        AffixRoster p = AffixRoster.build(List.of(affix("cursed", 5, 5, List.of("epic"), false)));
        Rarity rare = new Rarity("rare", "", 70, 5, 1, 1, 1, 1, 1, 1, null, null, List.of("*"));
        for (long s = 0; s < 128; s++) {
            assertTrue(p.pick(60, rare, 1, new SplitMix64(s)).isEmpty(), "epic-only affix blocked on a rare mob");
        }
    }

    @Test
    void combinedRollGrantsVariantAffix() {
        // A pool with a rarity affix (rarity-only) + a variant-exclusive affix (variant-only). The combined
        // roll for an epic base + horrific variant should be able to yield BOTH; the rarity never grants the
        // variant affix, and the variant never grants the rarity affix.
        AffixRoster p = AffixRoster.build(List.of(
                affix("stalwart", 3, 5, List.of("*"), false),
                variantAffix("venomous", List.of("horrific"))));
        Rarity epic = new Rarity("epic", "", 25, 25, 1, 1, 1, 1, 1, 1, null, null, List.of("*"));
        Variant horrific = new Variant("horrific", "", 0.15, 20, 1, 1, 1, 1, 1, 1, List.of("venomous"));
        boolean sawVenom = false;
        boolean sawStalwart = false;
        for (long s = 0; s < 256; s++) {
            List<String> ids = p.pick(60, epic, horrific, new SplitMix64(s)).stream().map(Affix::id).toList();
            // venomous only ever appears via the variant slot; stalwart only via the rarity slot.
            sawVenom |= ids.contains("venomous");
            sawStalwart |= ids.contains("stalwart");
            assertEquals(ids.size(), ids.stream().distinct().count(), "no dupes across hosts");
        }
        assertTrue(sawVenom, "the variant grants venomous");
        assertTrue(sawStalwart, "the rarity grants stalwart");
    }

    @Test
    void variantAffixNotGrantedWithoutTheVariant() {
        AffixRoster p = AffixRoster.build(List.of(variantAffix("venomous", List.of("horrific"))));
        Rarity epic = new Rarity("epic", "", 25, 25, 1, 1, 1, 1, 1, 2, null, null, List.of("*"));
        for (long s = 0; s < 128; s++) {
            // Rarity-only roll (no variant): venomous is variant-exclusive, so it never appears.
            assertTrue(p.pick(60, epic, (Variant) null, new SplitMix64(s)).isEmpty(),
                    "a variant-exclusive affix never rolls from the rarity alone");
        }
    }

    @Test
    void difficultyGatingBlocksHighMinDifficulty() {
        AffixRoster p = AffixRoster.build(List.of(affix("frost", 5, 40, List.of("*"), false)));
        Rarity leg = legendary();
        for (long s = 0; s < 128; s++) {
            assertTrue(p.pick(20, leg, 1, new SplitMix64(s)).isEmpty(), "minDiff-40 affix blocked at difficulty 20");
        }
    }

    @Test
    void worldGateDeniesAnAffixAcrossEveryHost() {
        // The per-world Pool.Affixes deny gate (1.0.2) ANDs into every draw: a denied affix never rolls
        // even with free slots, while the rest of the pool still fills them.
        AffixRoster p = pool();
        Rarity leg = legendary();
        for (long s = 0; s < 256; s++) {
            List<String> ids = p.pick(60, leg, null, 0, a -> !"armored".equals(a.id()), new SplitMix64(s))
                    .stream().map(Affix::id).toList();
            assertTrue(!ids.contains("armored"), "the world-denied affix never rolls");
        }
    }

    @Test
    void extraWorldSlotsAddAffixesButNeedAHost() {
        AffixRoster p = pool();
        Rarity oneSlot = new Rarity("epic", "", 25, 5, 1, 1, 1, 1, 1, 1, null, null, List.of("*"));
        // +2 extra world slots on a 1-slot rarity: rolls can now exceed one affix.
        boolean sawMoreThanOne = false;
        for (long s = 0; s < 256 && !sawMoreThanOne; s++) {
            sawMoreThanOne = p.pick(60, oneSlot, null, 2, a -> true, new SplitMix64(s)).size() > 1;
        }
        assertTrue(sawMoreThanOne, "extra world slots stack on the rarity slots");
        // A plain, variant-less mob has NO host to legitimize an affix: extra slots are a no-op.
        for (long s = 0; s < 64; s++) {
            assertTrue(p.pick(60, null, null, 3, a -> true, new SplitMix64(s)).isEmpty(),
                    "extra slots without a rarity/variant host roll nothing");
        }
    }
}
