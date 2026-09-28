package com.ziggfreed.mmomobscaling.scaling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.scaling.MobScaleFold.Clamps;
import com.ziggfreed.mmomobscaling.scaling.MobScaleFold.DifficultyStatCurve;
import com.ziggfreed.mmomobscaling.variant.Variant;

/**
 * The fold's mechanics on test-authored curves: the rarity and variant multipliers on DIFFICULTY (a tier
 * at X folds exactly like a plain mob at X times its multiplier, never clamped to a zone cap), the
 * geometric split of one effective-HP curve ({@code hp / in == ehp} exactly for any share), the shaped
 * damage axis (a shape of 1.0 is exactly the straight line; above it the curve bends upward and leaves the
 * tank axis alone), the multiplicative affix deltas, the safety clamps, and the composite rail that pulls
 * back {@code in} and never {@code hp}. No shipped number is restated here.
 */
class MobScaleFoldTest {

    /** A steep test curve: effective HP +8%/pt, damage +20%/pt on a straight line, share 0.75, rails 20x / 60x. */
    private static final DifficultyStatCurve CURVE = new DifficultyStatCurve(0.08, 0.75, 0.20, 1.0, 20.0, 60.0);
    /** Test rails wide enough that the curve, not a clamp, decides every fold below. */
    private static final Clamps RAILS = new Clamps(0.1, 1.0, 0.5, 0.5, 4.0);

    private static Rarity rarity(String id, double difficultyMultiplier, double loot, double xp) {
        return new Rarity(id, "", 25, 25, difficultyMultiplier, loot, xp, 2, "aura", List.of("*"));
    }

    private static Variant variant(double difficultyMultiplier, double loot, double xp) {
        return new Variant("horrific", "", 0.15, 20, difficultyMultiplier, loot, xp, 1, List.of("venomous"));
    }

    private static Affix affix(double hpDelta, double outDelta, double inDelta, double lootBonus) {
        return new Affix("x", "", "", null, 1, 5, List.of("*"), outDelta, inDelta, hpDelta, lootBonus,
                Affix.KIND_STAT, null, false);
    }

    private static Affix resisting(double resistancePercent) {
        return new Affix("ward", "", "", "Mmoscaling_Ward", 1, 5, List.of("*"), List.of(),
                0, 0, 0, 0, resistancePercent, Affix.KIND_STAT, null, true, null, null);
    }

    @Test
    void railFlagsSayExactlyWhereACeilingDecidesTheFactor() {
        // Under both rails the flags are clear and the factors are the unclamped lines.
        assertFalse(CURVE.effectiveHpRailed(100.0));
        assertFalse(CURVE.outRailed(100.0));
        assertEquals(1.0 + 99.0 * 0.08, CURVE.effectiveHp(100.0), 1e-12);
        // Far past both rails the flags are set and the factors sit ON the rails: raising the slope
        // would change nothing there, which is what the preview marks.
        assertTrue(CURVE.effectiveHpRailed(300.0));
        assertTrue(CURVE.outRailed(300.0));
        assertEquals(CURVE.maxEffectiveHpMult(), CURVE.effectiveHp(300.0), 1e-12);
        assertEquals(CURVE.maxOutDamageMult(), CURVE.outFactor(300.0), 1e-12);
        // Each flag flips exactly where its own line crosses its own rail (1 + (d-1)*0.08 > 20 at d > 238.5;
        // 1 + 0.2*(d-1) > 60 at d > 296), so the two axes rail independently.
        assertFalse(CURVE.effectiveHpRailed(238.0));
        assertTrue(CURVE.effectiveHpRailed(239.0));
        assertFalse(CURVE.outRailed(296.0));
        assertTrue(CURVE.outRailed(297.0));
        // The identity curve has no rail to hold anything: never railed, at any difficulty.
        assertFalse(DifficultyStatCurve.NONE.effectiveHpRailed(1_000_000.0));
        assertFalse(DifficultyStatCurve.NONE.outRailed(1_000_000.0));
    }

    @Test
    void identityCurveIsAllOnesWhateverTheDifficultyOrTier() {
        MobScaleResult plain = MobScaleFold.plain(12.0, MobScaleResult.SCOPE_HOSTILE, DifficultyStatCurve.NONE);
        assertEquals(1f, plain.hpMult());
        assertEquals(1f, plain.outDmgMult());
        assertEquals(1f, plain.inDmgMult());
        assertEquals(1f, plain.lootMult());
        assertEquals(1f, plain.xpMult());
        assertFalse(plain.hasRarity());
        assertFalse(plain.hasAffixes());
        assertEquals(12f, plain.difficulty());

        // The identity is genuinely neutral: a tier through NONE + no rails changes nothing but loot/xp.
        MobScaleResult tier = MobScaleFold.fold(rarity("epic", 2.0, 1.5, 1.3), null, List.of(), 150,
                MobScaleResult.SCOPE_HOSTILE, DifficultyStatCurve.NONE, Clamps.NONE);
        assertEquals(1f, tier.hpMult(), "no tuning hides in the fail-safe curve");
        assertEquals(1f, tier.outDmgMult());
        assertEquals(1f, tier.inDmgMult());
        assertEquals(1.5f, tier.lootMult(), 1e-6f);
        assertEquals(1.3f, tier.xpMult(), 1e-6f);
    }

    @Test
    void visibleAndInvisibleHalvesMultiplyBackToTheEffectiveHpExactly() {
        for (double share : new double[] {0.0, 0.25, 0.5, 0.85, 1.0}) {
            DifficultyStatCurve c = new DifficultyStatCurve(0.05, share, 0.2, 1.0, 40.0, 60.0);
            for (double d : new double[] {1, 7, 38, 120, 500}) {
                double ehp = c.effectiveHp(d);
                assertEquals(ehp, c.hpFactor(d) / c.inFactor(d), 1e-9,
                        "hp / in is exactly the effective HP at share " + share + ", d " + d);
                assertTrue(c.inFactor(d) <= 1.0 + 1e-12, "the invisible half never makes a mob softer");
                assertTrue(c.hpFactor(d) >= 1.0 - 1e-12, "the visible half never shrinks a mob");
            }
        }
        DifficultyStatCurve allVisible = new DifficultyStatCurve(0.05, 1.0, 0.2, 1.0, 40.0, 60.0);
        assertEquals(1.0, allVisible.inFactor(80), 1e-12, "share 1.0 leaves every hit landing for full damage");
        assertEquals(allVisible.effectiveHp(80), allVisible.hpFactor(80), 1e-12, "and puts it all on the bar");
        DifficultyStatCurve even = new DifficultyStatCurve(0.05, 0.5, 0.2, 1.0, 40.0, 60.0);
        assertEquals(Math.sqrt(even.effectiveHp(80)), even.hpFactor(80), 1e-12, "share 0.5 splits evenly");
        assertEquals(1.0 / Math.sqrt(even.effectiveHp(80)), even.inFactor(80), 1e-12);
    }

    @Test
    void aShapeOfOneIsExactlyTheStraightLineSlope() {
        // The identity that makes the knob legible: at shape 1.0 the scale IS the per-point slope, so the
        // damage axis reads 1 + (d - 1) * scale to the last bit, exactly as a plain slope would.
        DifficultyStatCurve line = new DifficultyStatCurve(0.05, 0.85, 0.2, 1.0, 1_000.0, 1_000.0);
        for (double d : new double[] {1, 2, 7, 38, 120, 500}) {
            assertEquals(1.0 + (d - 1.0) * 0.2, line.outFactor(d), 1e-12, "shape 1.0 is the plain slope at d " + d);
        }
    }

    @Test
    void aShapeAboveOneBendsTheDamageAxisUpwardAndLeavesTheTankAxisAlone() {
        DifficultyStatCurve line = new DifficultyStatCurve(0.05, 0.85, 0.2, 1.0, 1_000.0, 1_000.0);
        DifficultyStatCurve bent = new DifficultyStatCurve(0.05, 0.85, 0.2, 1.4, 1_000.0, 1_000.0);
        assertEquals(1.0, bent.outFactor(1), 1e-12, "the curve starts at exactly 1.0 whatever the shape");
        assertEquals(1.0 + 0.2, bent.outFactor(2), 1e-12, "one point above the baseline reads the scale itself (1 ^ shape = 1)");
        assertTrue(bent.outFactor(1.5) < line.outFactor(1.5), "under one point of distance the bent curve sits below the line");
        assertTrue(bent.outFactor(10) > line.outFactor(10), "past it the bent curve sits above the line");
        assertTrue(bent.outFactor(60) / bent.outFactor(30) > line.outFactor(60) / line.outFactor(30),
                "a shape above 1.0 widens the ratio between two difficulties: the late game grows faster than the early game");
        double previous = 1.0;
        for (double d : new double[] {2, 5, 20, 80, 300}) {
            assertTrue(bent.outFactor(d) > previous, "monotone in difficulty at d " + d);
            previous = bent.outFactor(d);
        }
        for (double d : new double[] {1, 7, 38, 120}) {
            assertEquals(line.effectiveHp(d), bent.effectiveHp(d), 1e-12, "the shape never touches the tank axis");
            assertEquals(line.hpFactor(d), bent.hpFactor(d), 1e-12);
            assertEquals(line.inFactor(d), bent.inFactor(d), 1e-12);
        }
        DifficultyStatCurve railed = new DifficultyStatCurve(0.05, 0.85, 0.2, 1.4, 1_000.0, 9.0);
        assertEquals(9.0, railed.outFactor(10_000), 1e-12, "the damage rail still caps the bent curve");
        // A non-positive shape is not a curve at all and reads as the straight line, in the record and in buildCurve.
        assertEquals(1.0, new DifficultyStatCurve(0.05, 0.85, 0.2, 0.0, 1_000.0, 1_000.0).outDamageShape(), 0.0);
        assertEquals(line.outFactor(38), new DifficultyStatCurve(0.05, 0.85, 0.2, -2.0, 1_000.0, 1_000.0).outFactor(38), 1e-12);
    }

    @Test
    void theTankAxisIsLinearInDifficultyAndBothAxesAreRailedAtTheirOwnCeilings() {
        assertEquals(1.0, CURVE.effectiveHp(1), 1e-12, "difficulty 1 is the baseline");
        assertEquals(1.0, CURVE.effectiveHp(0.2), 1e-12, "below 1 reads as 1");
        assertEquals(1.0 + 37 * 0.08, CURVE.effectiveHp(38), 1e-12);
        assertEquals(1.0 + 37 * 0.20, CURVE.outFactor(38), 1e-12);
        assertEquals(20.0, CURVE.effectiveHp(10_000), 1e-12, "the effective-HP rail");
        assertEquals(60.0, CURVE.outFactor(10_000), 1e-12, "the damage rail");
        MobScaleResult low = MobScaleFold.plain(3, MobScaleResult.SCOPE_HOSTILE, CURVE);
        MobScaleResult mid = MobScaleFold.plain(38, MobScaleResult.SCOPE_HOSTILE, CURVE);
        MobScaleResult high = MobScaleFold.plain(200, MobScaleResult.SCOPE_HOSTILE, CURVE);
        assertTrue(high.hpMult() > mid.hpMult() && mid.hpMult() > low.hpMult(), "hp rises with difficulty");
        assertTrue(high.outDmgMult() > mid.outDmgMult() && mid.outDmgMult() > low.outDmgMult(), "out rises");
        assertTrue(high.inDmgMult() < mid.inDmgMult() && mid.inDmgMult() < low.inDmgMult(), "in falls");
    }

    @Test
    void aRarityAtXFoldsExactlyLikeAPlainMobAtXTimesItsMultiplier() {
        Rarity tier = rarity("legendary", 2.4, 1.0, 1.0);
        for (double d : new double[] {1, 10, 30, 65}) {
            MobScaleResult rolled = MobScaleFold.fold(tier, null, List.of(), d, MobScaleResult.SCOPE_HOSTILE, CURVE, RAILS);
            MobScaleResult plainFurtherAlong = MobScaleFold.plain(d * 2.4, MobScaleResult.SCOPE_HOSTILE, CURVE);
            assertEquals(plainFurtherAlong.hpMult(), rolled.hpMult(), 1e-6f, "hp at d " + d);
            assertEquals(plainFurtherAlong.outDmgMult(), rolled.outDmgMult(), 1e-6f, "out at d " + d);
            assertEquals(plainFurtherAlong.inDmgMult(), rolled.inDmgMult(), 1e-6f, "in at d " + d);
            assertEquals((float) d, rolled.difficulty(), "the result carries the SPOT difficulty, not the product");
            assertTrue(rolled.hasRarity());
        }
    }

    @Test
    void rarityAndVariantMultipliersCompose() {
        Rarity tier = rarity("epic", 1.8, 1.5, 1.3);
        Variant overlay = variant(1.25, 1.3, 1.2);
        MobScaleResult both = MobScaleFold.fold(tier, overlay, List.of(), 20, MobScaleResult.SCOPE_HOSTILE, CURVE, RAILS);
        MobScaleResult plain = MobScaleFold.plain(20 * 1.8 * 1.25, MobScaleResult.SCOPE_HOSTILE, CURVE);
        assertEquals(plain.hpMult(), both.hpMult(), 1e-6f, "the two multipliers multiply on the difficulty");
        assertEquals(plain.outDmgMult(), both.outDmgMult(), 1e-6f);
        assertEquals(1.5f * 1.3f, both.lootMult(), 1e-6f, "loot = rarity x variant");
        assertEquals(1.3f * 1.2f, both.xpMult(), 1e-6f, "xp = rarity x variant");
        assertEquals("horrific", both.variantId());
        assertTrue(both.hasVariant() && both.hasRarity());

        // A variant on a plain base folds off the spot difficulty times its own multiplier alone.
        MobScaleResult plainBase = MobScaleFold.fold(null, overlay, List.of(), 20, MobScaleResult.SCOPE_HOSTILE, CURVE, RAILS);
        assertEquals(MobScaleFold.plain(25, MobScaleResult.SCOPE_HOSTILE, CURVE).hpMult(), plainBase.hpMult(), 1e-6f);
        assertFalse(plainBase.hasRarity());
        assertTrue(plainBase.hasVariant());
    }

    @Test
    void theCurveDifficultyIsNeverClampedToAZoneCap() {
        // A curve whose rails sit far away: a Boss at the top of a 200 band reads the curve at 600, and
        // that is visibly further along than a plain mob at 200, so the ladder still exists up there.
        DifficultyStatCurve roomy = new DifficultyStatCurve(0.05, 0.85, 0.3, 1.0, 1_000.0, 1_000.0);
        MobScaleResult boss = MobScaleFold.fold(rarity("boss", 3.0, 3.0, 2.0), null, List.of(), 200,
                MobScaleResult.SCOPE_HOSTILE, roomy, RAILS);
        MobScaleResult plainAtCap = MobScaleFold.plain(200, MobScaleResult.SCOPE_HOSTILE, roomy);
        assertEquals(600.0, MobScaleFold.curveDifficulty(200, rarity("boss", 3.0, 3.0, 2.0), null), 1e-12);
        assertTrue(boss.outDmgMult() > plainAtCap.outDmgMult() * 2.5, "the boss reads the curve at 600, not 200");
        assertEquals(roomy.outFactor(600), boss.outDmgMult(), 1e-4f);
    }

    @Test
    void affixDeltasMultiplyTheCurve() {
        // Stalwart-like +15% hp; +20% out; -10% in; +20% loot passes.
        Rarity tier = rarity("epic", 2.0, 1.5, 1.3);
        MobScaleResult r = MobScaleFold.fold(tier, null, List.of(affix(0.15, 0.2, -0.1, 0.2)), 20,
                MobScaleResult.SCOPE_HOSTILE, CURVE, RAILS);
        double dEff = 40;
        assertEquals((float) (CURVE.hpFactor(dEff) * 1.15), r.hpMult(), 1e-5f, "hp = curve x (1 + delta)");
        assertEquals((float) (CURVE.outFactor(dEff) * 1.2), r.outDmgMult(), 1e-5f, "out = curve x (1 + delta)");
        assertEquals((float) (CURVE.inFactor(dEff) * 0.9), r.inDmgMult(), 1e-5f, "in = curve x (1 + delta)");
        assertEquals(1.5f * 1.2f, r.lootMult(), 1e-5f, "loot passes x (1 + bonus)");
        assertEquals(1, r.affixIds().length);
    }

    @Test
    void anAffixOnAPlainMobStillFolds() {
        MobScaleResult r = MobScaleFold.fold(null, null, List.of(affix(0.15, 0, 0, 0)), 30,
                MobScaleResult.SCOPE_HOSTILE, CURVE, RAILS);
        assertEquals((float) (CURVE.hpFactor(30) * 1.15), r.hpMult(), 1e-5f, "the delta applies with no tier");
        assertTrue(r.hasAffixes());
        assertFalse(r.hasRarity());
    }

    @Test
    void theSafetyClampsBoundEachAxis() {
        Clamps tight = new Clamps(0.5, 1.0, 0.9, 0.5, 2.0);
        // A negative hp delta that would take hp under the floor; a negative out delta under its floor; a
        // positive in delta over the 1.0 ceiling; a loot product over the ceiling.
        MobScaleResult r = MobScaleFold.fold(rarity("epic", 1.0, 3.0, 1.0), null,
                List.of(affix(-0.9, -0.9, 5.0, 0.0)), 1, MobScaleResult.SCOPE_HOSTILE, CURVE, tight);
        assertEquals(0.5f, r.hpMult(), 1e-6f, "MinHpMult");
        assertEquals(0.9f, r.outDmgMult(), 1e-6f, "MinOutDamageMult");
        assertEquals(1.0f, r.inDmgMult(), 1e-6f, "MaxInDamageMult");
        assertEquals(2.0f, r.lootMult(), 1e-6f, "MaxLootMult");
        MobScaleResult little = MobScaleFold.fold(rarity("epic", 1.0, 0.1, 1.0), null, List.of(), 1,
                MobScaleResult.SCOPE_HOSTILE, CURVE, tight);
        assertEquals(0.5f, little.lootMult(), 1e-6f, "MinLootMult");

        // The no-rail identity lets the same fold through untouched.
        MobScaleResult free = MobScaleFold.fold(rarity("epic", 1.0, 3.0, 1.0), null,
                List.of(affix(-0.9, -0.9, 5.0, 0.0)), 1, MobScaleResult.SCOPE_HOSTILE, CURVE, Clamps.NONE);
        assertEquals(0.1f, free.hpMult(), 1e-6f);
        assertEquals(6.0f, free.inDmgMult(), 1e-6f);
        assertEquals(3.0f, free.lootMult(), 1e-6f);
    }

    @Test
    void theCompositeRailPullsBackTheInvisibleTermAndNeverTheVisibleOne() {
        // A curve whose rail the plain fold reaches exactly at the top: an affix's +hp then pushes
        // hp / in past it, and the correction lands on `in`.
        DifficultyStatCurve railed = new DifficultyStatCurve(0.1, 0.85, 0.1, 1.0, 10.0, 10.0);
        double atRail = 91; // 1 + 90 * 0.1 = 10.0 = the rail
        MobScaleResult plain = MobScaleFold.plain(atRail, MobScaleResult.SCOPE_HOSTILE, railed);
        assertEquals(10.0, plain.hpMult() / plain.inDmgMult(), 1e-4, "the plain fold sits on the rail");

        MobScaleResult stalwart = MobScaleFold.fold(null, null, List.of(affix(0.5, 0, 0, 0)), atRail,
                MobScaleResult.SCOPE_HOSTILE, railed, Clamps.NONE);
        assertEquals((float) (railed.hpFactor(atRail) * 1.5), stalwart.hpMult(), 1e-5f,
                "hp keeps the affix bonus in full - the health bar is what the player reads");
        assertTrue(stalwart.inDmgMult() > plain.inDmgMult(), "the invisible term is raised instead");
        assertEquals(10.0, stalwart.hpMult() / stalwart.inDmgMult(), 1e-4, "and the product sits back on the rail");
    }

    @Test
    void theCompositeRailCountsADeclaredResistanceMirror() {
        DifficultyStatCurve railed = new DifficultyStatCurve(0.1, 0.85, 0.1, 1.0, 10.0, 10.0);

        double atRail = 91;
        MobScaleResult warded = MobScaleFold.fold(null, null, List.of(resisting(0.4)), atRail,
                MobScaleResult.SCOPE_HOSTILE, railed, Clamps.NONE);
        MobScaleResult plain = MobScaleFold.plain(atRail, MobScaleResult.SCOPE_HOSTILE, railed);
        assertEquals(plain.hpMult(), warded.hpMult(), 1e-6f, "the mirror never touches hp");
        assertEquals(10.0, warded.hpMult() / (warded.inDmgMult() * (1.0 - 0.4)), 1e-4,
                "hp / (in x surviving fraction) is held at the rail");
        assertTrue(warded.inDmgMult() > plain.inDmgMult(), "in is raised to pay for the resistance");

        // Well under the rail the mirror changes nothing: it is bound arithmetic, not a stat.
        MobScaleResult low = MobScaleFold.fold(null, null, List.of(resisting(0.4)), 5,
                MobScaleResult.SCOPE_HOSTILE, railed, Clamps.NONE);
        assertEquals(MobScaleFold.plain(5, MobScaleResult.SCOPE_HOSTILE, railed).inDmgMult(), low.inDmgMult(), 1e-6f);
    }

    @Test
    void boundEffectiveHpArithmetic() {
        assertEquals(0.5, MobScaleFold.boundEffectiveHp(4.0, 0.5, 0.0, 10.0), 1e-12, "under the rail: untouched");
        assertEquals(0.5, MobScaleFold.boundEffectiveHp(5.0, 0.5, 0.0, 10.0), 1e-12, "on the rail: untouched");
        assertEquals(0.8, MobScaleFold.boundEffectiveHp(8.0, 0.5, 0.0, 10.0), 1e-12, "over: in = hp / rail");
        assertEquals(8.0 / (10.0 * 0.6), MobScaleFold.boundEffectiveHp(8.0, 0.5, 0.4, 10.0), 1e-12,
                "a resistance divides the surviving fraction into the rail");
        assertEquals(0.5, MobScaleFold.boundEffectiveHp(50.0, 0.5, 1.0, 10.0), 1e-12,
                "outright immunity cannot be bounded here and is left to the effect's own validation");
        assertEquals(4.0, MobScaleFold.boundEffectiveHp(40.0, 0.5, 0.0, 10.0), 1e-12,
                "a visible hp already past the rail lifts in past 1.0 rather than touching hp");
    }
}
