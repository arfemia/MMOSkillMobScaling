package com.ziggfreed.mmomobscaling.pages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.mmomobscaling.pages.ScalingPreview.Sample;
import com.ziggfreed.mmomobscaling.pages.ScalingPreview.Tier;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.scaling.MobScaleFold;
import com.ziggfreed.mmomobscaling.scaling.MobScaleFold.Clamps;
import com.ziggfreed.mmomobscaling.scaling.MobScaleFold.DifficultyStatCurve;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;

/**
 * The admin preview's arithmetic on test-authored curves and tiers: a sample is the fold's own plain
 * result, its time to kill is {@code hp / in}, the ladder is plain first then weakest to strongest with
 * each rung read at the fold's own {@code curveDifficulty}, and the rail flags mark where a ceiling, not
 * the slope, decided a rung. No shipped number is restated here.
 */
class ScalingPreviewTest {

    /** The same steep test curve MobScaleFoldTest uses: +8%/pt toughness, +20%/pt damage, share 0.75, rails 20x / 60x. */
    private static final DifficultyStatCurve CURVE = new DifficultyStatCurve(0.08, 0.75, 0.20, 1.0, 20.0, 60.0);
    private static final Clamps RAILS = new Clamps(0.1, 1.0, 0.5, 0.5, 4.0);

    private static Rarity rarity(String id, double difficultyMultiplier) {
        return new Rarity(id, "", 25, 25, difficultyMultiplier, 1.0, 1.0, 2, null, List.of("*"));
    }

    @Test
    void difficultyOneIsTheUnscaledMob() {
        Sample s = ScalingPreview.sample(CURVE, 1.0);
        assertEquals(1.0, s.hp(), 1e-6);
        assertEquals(1.0, s.out(), 1e-6);
        assertEquals(1.0, s.in(), 1e-6);
        assertEquals(1.0, s.timeToKill(), 1e-6);
        assertFalse(s.railed());
    }

    @Test
    void aSampleIsTheFoldsOwnPlainResultAndKillTimeIsHpOverIn() {
        double d = 41.0;
        Sample s = ScalingPreview.sample(CURVE, d);
        MobScaleResult plain = MobScaleFold.plain(d, MobScaleResult.SCOPE_HOSTILE, CURVE);
        assertEquals(plain.hpMult(), s.hp(), 1e-6);
        assertEquals(plain.outDmgMult(), s.out(), 1e-6);
        assertEquals(plain.inDmgMult(), s.in(), 1e-6);
        assertEquals(s.hp() / s.in(), s.timeToKill(), 1e-9);
        // hp / in is the effective-HP curve itself, the number the curve is derived to hold (float-stamped
        // factors, so the tolerance is the float's).
        assertEquals(CURVE.effectiveHp(d), s.timeToKill(), 1e-3);
        assertEquals(d, s.difficulty(), 0.0);
    }

    @Test
    void ladderIsPlainFirstThenWeakestToStrongestEachReadAtItsOwnDifficulty() {
        double d = 30.0;
        List<Rarity> outOfOrder = List.of(rarity("legendary", 2.4), rarity("rare", 1.35), rarity("epic", 1.8));
        List<Tier> ladder = ScalingPreview.ladder(CURVE, RAILS, outOfOrder, d);

        assertEquals(4, ladder.size());
        assertTrue(ladder.get(0).plain());
        assertNull(ladder.get(0).rarity());
        assertEquals(1.0, ladder.get(0).difficultyMultiplier(), 0.0);
        assertEquals("rare", ladder.get(1).rarity().id());
        assertEquals("epic", ladder.get(2).rarity().id());
        assertEquals("legendary", ladder.get(3).rarity().id());

        for (Tier tier : ladder) {
            double dEff = MobScaleFold.curveDifficulty(d, tier.rarity(), null);
            assertEquals(dEff, tier.sample().difficulty(), 0.0, tier.rarity() + " reads the curve at d * multiplier");
            MobScaleResult folded = MobScaleFold.fold(tier.rarity(), null, List.of(), d,
                    MobScaleResult.SCOPE_HOSTILE, CURVE, RAILS);
            assertEquals(folded.hpMult(), tier.sample().hp(), 1e-6, "the rung is the fold's own result");
            assertEquals(folded.outDmgMult(), tier.sample().out(), 1e-6);
            assertEquals(folded.inDmgMult(), tier.sample().in(), 1e-6);
        }
        // Strength order is fold order: every rung is at least as tough and as hard-hitting as the one below.
        for (int i = 1; i < ladder.size(); i++) {
            assertTrue(ladder.get(i).sample().timeToKill() >= ladder.get(i - 1).sample().timeToKill());
            assertTrue(ladder.get(i).sample().out() >= ladder.get(i - 1).sample().out());
        }
    }

    @Test
    void ladderMarksTheRungsACeilingDecidedAndFoldsThemAlike() {
        // Rails low enough that the plain mob sits under them while the two strongest tiers sit past them.
        DifficultyStatCurve railed = new DifficultyStatCurve(0.08, 0.75, 0.20, 1.0, 3.0, 4.0);
        double d = 20.0; // plain ehp 2.52, out 4.8 -> out already railed; ehp rails from d * 1.35 up
        List<Tier> ladder = ScalingPreview.ladder(railed, RAILS,
                List.of(rarity("rare", 1.35), rarity("epic", 1.8), rarity("legendary", 2.4)), d);

        Sample plain = ladder.get(0).sample();
        assertFalse(plain.hpRailed(), "the plain mob is still under the toughness rail");
        assertTrue(plain.outRailed(), "the plain mob already sits on the damage rail");

        Sample epic = ladder.get(2).sample();
        Sample legendary = ladder.get(3).sample();
        assertTrue(epic.hpRailed() && epic.outRailed());
        assertTrue(legendary.hpRailed() && legendary.outRailed());
        // Two railed rungs fold identical: the ceiling, not the tier, decided both - which is exactly what
        // the marker exists to show an owner.
        assertEquals(epic.hp(), legendary.hp(), 1e-6);
        assertEquals(epic.out(), legendary.out(), 1e-6);
        assertEquals(epic.timeToKill(), legendary.timeToKill(), 1e-6);
        assertEquals(railed.maxEffectiveHpMult(), legendary.timeToKill(), 1e-3);
    }

    @Test
    void ladderWithNoTiersIsJustThePlainMob() {
        List<Tier> ladder = ScalingPreview.ladder(CURVE, RAILS, List.of(), 12.0);
        assertEquals(1, ladder.size());
        assertTrue(ladder.get(0).plain());
    }
}
