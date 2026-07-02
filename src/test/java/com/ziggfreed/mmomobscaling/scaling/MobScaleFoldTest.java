package com.ziggfreed.mmomobscaling.scaling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.rarity.Rarity;

/** Verifies the additive-affix fold + the global safety-cap clamps in {@link MobScaleFold}. */
class MobScaleFoldTest {

    private static Rarity rarity(double hp, double out, double in, double loot, double xp) {
        return new Rarity("epic", "", 25, 25, hp, out, in, loot, xp, 2, "aura", null, List.of("*"));
    }

    private static Affix affix(double hpDelta, double outDelta, double inDelta, double lootBonus) {
        return new Affix("x", "", "", null, 1, 5, List.of("*"), outDelta, inDelta, hpDelta, lootBonus,
                Affix.KIND_STAT, null, false);
    }

    @Test
    void plainIsAllOnes() {
        MobScaleResult r = MobScaleFold.plain(12.0, MobScaleResult.SCOPE_HOSTILE);
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
        MobScaleResult r = MobScaleFold.fold(null, List.of(affix(1, 1, -1, 1)), 30, MobScaleResult.SCOPE_HOSTILE);
        assertEquals(1f, r.hpMult(), "no rarity -> plain, affixes ignored");
        assertFalse(r.hasAffixes());
    }

    @Test
    void rarityOnlyPassesThrough() {
        MobScaleResult r = MobScaleFold.fold(rarity(2.2, 1.9, 0.7, 1.5, 1.3), List.of(), 40, MobScaleResult.SCOPE_HOSTILE);
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
                List.of(affix(0.15, 0.2, -0.1, 0.2)), 40, MobScaleResult.SCOPE_HOSTILE);
        assertEquals(2.15f, r.hpMult(), 1e-5f, "hp additive");
        assertEquals(1.7f, r.outDmgMult(), 1e-5f, "out additive");
        assertEquals(0.7f, r.inDmgMult(), 1e-5f, "in additive");
        assertEquals(1.5f * 1.2f, r.lootMult(), 1e-5f, "loot multiplicative with affix bonus");
        assertEquals(1, r.affixIds().length);
    }

    @Test
    void clampsToSafetyCaps() {
        // Legendary + multiple big affixes would blow past every cap; the fold clamps.
        MobScaleResult r = MobScaleFold.fold(rarity(3.8, 2.6, 0.55, 2.0, 1.6),
                List.of(affix(2.0, 2.0, -2.0, 5.0), affix(2.0, 2.0, -2.0, 5.0)), 80, MobScaleResult.SCOPE_HOSTILE);
        assertEquals((float) MobScaleFold.MAX_HEALTH_MULT, r.hpMult(), 1e-5f, "hp capped at 4.5");
        assertEquals((float) MobScaleFold.OUT_DMG_MAX, r.outDmgMult(), 1e-5f, "out capped at 3.0");
        assertEquals((float) MobScaleFold.IN_DMG_MIN, r.inDmgMult(), 1e-5f, "in floored at 0.5 (x2 effective HP ceiling)");
        assertEquals((float) MobScaleFold.LOOT_MULT_MAX, r.lootMult(), 1e-5f, "loot capped at 3.0");
    }
}
