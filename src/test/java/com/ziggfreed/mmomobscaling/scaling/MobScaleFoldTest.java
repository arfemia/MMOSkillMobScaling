package com.ziggfreed.mmomobscaling.scaling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.variant.Variant;

/** Verifies the additive-affix fold + the multiplicative variant overlay + the global safety-cap clamps. */
class MobScaleFoldTest {

    private static Rarity rarity(double hp, double out, double in, double loot, double xp) {
        return new Rarity("epic", "", 25, 25, hp, out, in, loot, xp, 2, "aura", null, List.of("*"));
    }

    private static Variant variant(double hp, double out, double in, double loot, double xp) {
        return new Variant("horrific", "", 0.15, 20, hp, out, in, loot, xp, 1, List.of("venomous"));
    }

    private static Affix affix(double hpDelta, double outDelta, double inDelta, double lootBonus) {
        return new Affix("x", "", "", null, 1, 5, List.of("*"), outDelta, inDelta, hpDelta, lootBonus,
                Affix.KIND_STAT, null, false);
    }

    @Test
    void plainIsAllOnes() {
        MobScaleResult r = MobScaleFold.plain(12.0, MobScaleResult.SCOPE_HOSTILE,
                MobScaleFold.DifficultyStatCurve.NONE);
        assertEquals(1f, r.hpMult());
        assertEquals(1f, r.outDmgMult());
        assertEquals(1f, r.inDmgMult());
        assertEquals(1f, r.lootMult());
        assertEquals(1f, r.xpMult());
        assertFalse(r.hasRarity());
        assertFalse(r.hasAffixes());
        assertEquals(12f, r.difficulty());
    }

    @Test
    void nullRarityFoldsToPlain() {
        MobScaleResult r = MobScaleFold.fold(null, List.of(affix(1, 1, -1, 1)), 30, MobScaleResult.SCOPE_HOSTILE,
                MobScaleFold.DifficultyStatCurve.NONE);
        assertEquals(1f, r.hpMult(), "no rarity -> plain, affixes ignored");
        assertFalse(r.hasAffixes());
    }

    @Test
    void rarityOnlyPassesThrough() {
        MobScaleResult r = MobScaleFold.fold(rarity(2.2, 1.9, 0.7, 1.5, 1.3), List.of(), 40,
                MobScaleResult.SCOPE_HOSTILE, MobScaleFold.DifficultyStatCurve.NONE);
        assertEquals(2.2f, r.hpMult(), 1e-5f);
        assertEquals(1.9f, r.outDmgMult(), 1e-5f);
        assertEquals(0.7f, r.inDmgMult(), 1e-5f);
        assertEquals(1.5f, r.lootMult(), 1e-5f);
        assertEquals(1.3f, r.xpMult(), 1e-5f);
        assertTrue(r.hasRarity());
    }

    @Test
    void affixDeltasAreAdditive() {
        // Stalwart-like +0.15 hp; a +0.2 out; a -0.1 in; +0.2 loot bonus.
        MobScaleResult r = MobScaleFold.fold(rarity(2.0, 1.5, 0.8, 1.5, 1.3),
                List.of(affix(0.15, 0.2, -0.1, 0.2)), 40, MobScaleResult.SCOPE_HOSTILE,
                MobScaleFold.DifficultyStatCurve.NONE);
        assertEquals(2.15f, r.hpMult(), 1e-5f, "hp additive");
        assertEquals(1.7f, r.outDmgMult(), 1e-5f, "out additive");
        assertEquals(0.7f, r.inDmgMult(), 1e-5f, "in additive");
        assertEquals(1.5f * 1.2f, r.lootMult(), 1e-5f, "loot multiplicative with affix bonus");
        assertEquals(1, r.affixIds().length);
    }

    @Test
    void variantStacksMultiplicativelyOverRarity() {
        // Epic base * horrific overlay per channel: hp 2.0*1.5=3.0, out 1.5*1.4=2.1, in 0.8*0.9=0.72,
        // loot 1.5*1.3, xp 1.3*1.2. Identity curve, all within caps.
        MobScaleResult r = MobScaleFold.fold(rarity(2.0, 1.5, 0.8, 1.5, 1.3),
                variant(1.5, 1.4, 0.9, 1.3, 1.2), List.of(), 40, MobScaleResult.SCOPE_HOSTILE,
                MobScaleFold.DifficultyStatCurve.NONE);
        assertEquals(3.0f, r.hpMult(), 1e-4f, "hp = rarity * variant");
        assertEquals(2.1f, r.outDmgMult(), 1e-4f, "out = rarity * variant");
        assertEquals(0.72f, r.inDmgMult(), 1e-4f, "in = rarity * variant (tankier)");
        assertEquals(1.5f * 1.3f, r.lootMult(), 1e-4f, "loot = rarity * variant");
        assertEquals(1.3f * 1.2f, r.xpMult(), 1e-4f, "xp = rarity * variant");
        assertEquals("horrific", r.variantId(), "variant id recorded");
        assertTrue(r.hasVariant());
        assertTrue(r.hasRarity());
    }

    @Test
    void variantWithoutRarityFoldsOffBaseOne() {
        // No base rarity: base = 1.0, the variant multiplier IS the result ("Horrific Spider", plain base).
        MobScaleResult r = MobScaleFold.fold(null, variant(1.5, 1.4, 0.9, 1.3, 1.2), List.of(), 30,
                MobScaleResult.SCOPE_HOSTILE, MobScaleFold.DifficultyStatCurve.NONE);
        assertEquals(1.5f, r.hpMult(), 1e-4f, "hp = 1.0 base * variant");
        assertEquals(1.4f, r.outDmgMult(), 1e-4f);
        assertFalse(r.hasRarity(), "no base rarity");
        assertTrue(r.hasVariant(), "but a variant overlay");
    }

    @Test
    void clampsToSafetyCaps() {
        // Legendary + multiple big affixes would blow past every cap; the fold clamps.
        MobScaleResult r = MobScaleFold.fold(rarity(3.8, 2.6, 0.55, 2.0, 1.6),
                List.of(affix(2.0, 2.0, -2.0, 5.0), affix(2.0, 2.0, -2.0, 5.0)), 80, MobScaleResult.SCOPE_HOSTILE,
                MobScaleFold.DifficultyStatCurve.NONE);
        assertEquals((float) MobScaleFold.MAX_HEALTH_MULT, r.hpMult(), 1e-5f, "hp capped at 4.5");
        assertEquals((float) MobScaleFold.OUT_DMG_MAX, r.outDmgMult(), 1e-5f, "out capped at 3.0");
        assertEquals((float) MobScaleFold.IN_DMG_MIN, r.inDmgMult(), 1e-5f, "in floored at 0.5 (x2 effective HP ceiling)");
        assertEquals((float) MobScaleFold.LOOT_MULT_MAX, r.lootMult(), 1e-5f, "loot capped at 3.0");
    }

    @Test
    void plainScalesWithDifficulty() {
        // A steep curve: HP +8%/pt, out +2%/pt, in -0.2%/pt, capped 20x / 8x / floor 0.5.
        var curve = new MobScaleFold.DifficultyStatCurve(0.08, 0.02, 0.002, 20.0, 8.0, 0.5);

        // Difficulty 1 is the baseline (no scaling): hpFactor = 1 + (1-1)*0.08 = 1.
        assertEquals(1f, MobScaleFold.plain(1, MobScaleResult.SCOPE_HOSTILE, curve).hpMult(),
                "difficulty 1 = baseline");

        // hpFactor = clamp(1 + (max(1,d)-1)*0.08, 1, 20).
        assertEquals(1.16f, MobScaleFold.plain(3, MobScaleResult.SCOPE_HOSTILE, curve).hpMult(), 1e-4f);
        assertEquals(3.96f, MobScaleFold.plain(38, MobScaleResult.SCOPE_HOSTILE, curve).hpMult(), 1e-4f);
        assertEquals(16.92f, MobScaleFold.plain(200, MobScaleResult.SCOPE_HOSTILE, curve).hpMult(), 1e-4f);

        // At difficulty 38: out rises (1 + 37*0.02), in falls (1 - 37*0.002).
        MobScaleResult mid = MobScaleFold.plain(38, MobScaleResult.SCOPE_HOSTILE, curve);
        assertEquals(1.74f, mid.outDmgMult(), 1e-4f, "outFactor = 1 + 37*0.02");
        assertEquals(0.926f, mid.inDmgMult(), 1e-4f, "inFactor = 1 - 37*0.002");

        // Monotone across difficulty: hp + out rise, in falls.
        MobScaleResult low = MobScaleFold.plain(3, MobScaleResult.SCOPE_HOSTILE, curve);
        MobScaleResult high = MobScaleFold.plain(200, MobScaleResult.SCOPE_HOSTILE, curve);
        assertTrue(high.hpMult() > mid.hpMult() && mid.hpMult() > low.hpMult(), "hp rises with difficulty");
        assertTrue(high.outDmgMult() > mid.outDmgMult() && mid.outDmgMult() > low.outDmgMult(),
                "out rises with difficulty");
        assertTrue(high.inDmgMult() < mid.inDmgMult() && mid.inDmgMult() < low.inDmgMult(),
                "in falls with difficulty");
    }

    @Test
    void foldIsCurveTimesRarity() {
        // The same steep curve; a rarity multiplies the curve-scaled base per channel.
        var curve = new MobScaleFold.DifficultyStatCurve(0.08, 0.02, 0.002, 20.0, 8.0, 0.5);
        MobScaleResult r = MobScaleFold.fold(rarity(2.0, 1.5, 0.8, 1.5, 1.3), List.of(), 38,
                MobScaleResult.SCOPE_HOSTILE, curve);
        // hp: curve.hpFactor(38)=3.96 * rarity 2.0; out: 1.74 * 1.5; in: 0.926 * 0.8. All below the curve caps.
        assertEquals(3.96f * 2.0f, r.hpMult(), 1e-3f, "curve HP times rarity HP");
        assertEquals(1.74f * 1.5f, r.outDmgMult(), 1e-3f, "curve out times rarity out");
        assertEquals(0.926f * 0.8f, r.inDmgMult(), 1e-3f, "curve in times rarity in");
    }
}
