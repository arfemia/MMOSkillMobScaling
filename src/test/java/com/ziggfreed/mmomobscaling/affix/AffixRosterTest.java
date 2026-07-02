package com.ziggfreed.mmomobscaling.affix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.common.util.SplitMix64;

/** Slot-count, resistance-uniqueness, no-duplicate, gating + determinism tests for {@link AffixRoster}. */
class AffixRosterTest {

    private static Affix affix(String id, double weight, double minDiff, List<String> allowedRarities, boolean resist) {
        return new Affix(id, "", "", null, weight, minDiff, allowedRarities, 0, 0, 0, 0, Affix.KIND_STAT, null, resist);
    }

    private static Rarity legendary() {
        return new Rarity("legendary", "", 5, 50, 1, 1, 1, 1, 1, 3, null, List.of("*"));
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
        Rarity rare = new Rarity("rare", "", 70, 5, 1, 1, 1, 1, 1, 1, null, List.of("*"));
        for (long s = 0; s < 128; s++) {
            assertTrue(p.pick(60, rare, 1, new SplitMix64(s)).isEmpty(), "epic-only affix blocked on a rare mob");
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
}
