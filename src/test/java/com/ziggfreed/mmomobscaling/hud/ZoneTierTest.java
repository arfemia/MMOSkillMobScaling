package com.ziggfreed.mmomobscaling.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.mmomobscaling.scaling.MobScaleFold.DifficultyStatCurve;

/**
 * Guards the zone-threat banding on the derived threat ratio ({@code outFactor(difficulty) /
 * outFactor(power)} off one curve): a matched fight is FAIR at EVERY power on every curve shape, the same
 * absolute delta legitimately reads different tiers at different powers, the band edges sit where the
 * time-to-die derivation puts them and mirror each other, and every edge case (a power past the caps, a
 * power at or below the curve's origin, a railed curve, the identity curve, a non-finite ratio) reads a
 * deliberate tier. Every curve here is test-authored, never the shipped one. Also the tier lang-key
 * convention the HUD renders through (every key must exist in {@code mmomobscaling.lang};
 * {@code ScalingLangTest} covers the file side).
 */
class ZoneTierTest {

    /** A straight damage axis (shape 1.0): the scale is a plain per-point slope. */
    private static final DifficultyStatCurve LINE = new DifficultyStatCurve(0.05, 0.85, 0.285, 1.0, 1_000.0, 1_000.0);
    /** A damage axis bent upward (shape above 1.0), the shape the parity derivation lands on. */
    private static final DifficultyStatCurve BENT = new DifficultyStatCurve(0.05, 0.85, 0.0342, 1.4, 1_000.0, 1_000.0);
    /** The bent curve with a low damage rail, so the outgoing factor pins well inside the band. */
    private static final DifficultyStatCurve RAILED = new DifficultyStatCurve(0.05, 0.85, 0.0342, 1.4, 1_000.0, 9.0);

    @Test
    void aMatchedFightIsFairAtEveryPowerOnEveryCurveShape() {
        // The property the old absolute delta could not deliver: the matched reading is 1.0 by construction,
        // whatever the curve does, so it is FAIR from the origin to well past any cap.
        for (DifficultyStatCurve curve : List.of(LINE, BENT, RAILED, DifficultyStatCurve.NONE)) {
            for (double power = 0.0; power <= 400.0; power += 0.5) {
                assertEquals(1.0, ZoneTier.threatRatio(curve, power, power), 0.0, "matched ratio at power " + power);
                assertEquals(ZoneTier.FAIR, ZoneTier.classify(curve, power, power),
                        "matched power is fair at power " + power + " on " + curve);
            }
        }
    }

    @Test
    void theSameAbsoluteDeltaReadsDifferentTiersAtDifferentPowers() {
        // Fifteen points over a low power multiply the hit far more than fifteen points over a high one, on a
        // straight curve and a bent one alike: that is why the input is a ratio and not a delta.
        for (DifficultyStatCurve curve : List.of(LINE, BENT)) {
            double low = ZoneTier.threatRatio(curve, 25.0, 10.0);
            double high = ZoneTier.threatRatio(curve, 115.0, 100.0);
            assertTrue(low > high, "+15 is a larger ratio over power 10 than over power 100 on " + curve);
            assertEquals(ZoneTier.HARD, ZoneTier.classify(curve, 25.0, 10.0), "+15 over power 10 on " + curve);
            assertEquals(ZoneTier.FAIR, ZoneTier.classify(curve, 115.0, 100.0), "+15 over power 100 on " + curve);
        }
    }

    @Test
    void theBandsSitWhereTimeToDiePutsThem() {
        // FAIR is within a quarter either way: the open band (0.8, 1.25).
        assertEquals(ZoneTier.FAIR, ZoneTier.fromThreatRatio(1.0), "matched is fair");
        assertEquals(ZoneTier.FAIR, ZoneTier.fromThreatRatio(1.249));
        assertEquals(ZoneTier.FAIR, ZoneTier.fromThreatRatio(0.801));
        assertEquals(ZoneTier.HARD, ZoneTier.fromThreatRatio(ZoneTier.FAIR_TOLERANCE), "dying a quarter faster is hard");
        assertEquals(ZoneTier.EASY, ZoneTier.fromThreatRatio(1.0 / ZoneTier.FAIR_TOLERANCE), "lasting a quarter longer is easy");
        // HARD and EASY run out to a doubling of the fair edge, 2.5x; DEADLY and TRIVIAL lie beyond.
        double outer = ZoneTier.FAIR_TOLERANCE * ZoneTier.BAND_MULTIPLE;
        assertEquals(2.5, outer, 0.0, "a quarter, doubled");
        assertEquals(ZoneTier.HARD, ZoneTier.fromThreatRatio(2.499));
        assertEquals(ZoneTier.DEADLY, ZoneTier.fromThreatRatio(outer), "dying 2.5 times faster is deadly");
        assertEquals(ZoneTier.DEADLY, ZoneTier.fromThreatRatio(80.0));
        assertEquals(ZoneTier.EASY, ZoneTier.fromThreatRatio(0.401));
        assertEquals(ZoneTier.TRIVIAL, ZoneTier.fromThreatRatio(1.0 / outer), "lasting 2.5 times longer is trivial");
        assertEquals(ZoneTier.TRIVIAL, ZoneTier.fromThreatRatio(0.05));
    }

    @Test
    void theBandsMirrorAroundMatchedAndNeverStepBackwards() {
        // Geometric symmetry: a zone that halves the time-to-die and one that doubles it sit the same distance
        // from FAIR on opposite sides. Sampled away from the exact edges, which the test above pins.
        for (double ratio : new double[] {1.05, 1.15, 1.24, 1.3, 1.7, 2.2, 2.6, 4.0, 12.0}) {
            ZoneTier up = ZoneTier.fromThreatRatio(ratio);
            ZoneTier down = ZoneTier.fromThreatRatio(1.0 / ratio);
            assertEquals(ZoneTier.FAIR.ordinal() - up.ordinal(), down.ordinal() - ZoneTier.FAIR.ordinal(),
                    ratio + " and its inverse mirror around FAIR");
        }
        ZoneTier previous = ZoneTier.TRIVIAL;
        for (double ratio = 0.01; ratio <= 20.0; ratio *= 1.01) {
            ZoneTier tier = ZoneTier.fromThreatRatio(ratio);
            assertTrue(tier.ordinal() >= previous.ordinal(), "a harder ratio never reads an easier tier at " + ratio);
            previous = tier;
        }
    }

    @Test
    void aPowerPastTheDifficultyCapsReadsHonestly() {
        // The curve is read at the RAW power. A power the zone cannot reach (past a MaxCap of 200) reads a
        // capped zone as easy or trivial; a power under a MinCap of 1 reads the floor as fair, because the
        // curve floors both sides at 1 and a difficulty-1 mob is as weak as a mob gets.
        for (DifficultyStatCurve curve : List.of(LINE, BENT)) {
            assertTrue(ZoneTier.classify(curve, 200.0, 260.0).ordinal() < ZoneTier.FAIR.ordinal(),
                    "a capped zone is easy for a power past the cap on " + curve);
            assertEquals(ZoneTier.FAIR, ZoneTier.classify(curve, 1.0, 0.5), "a power under the floor at the floor");
            assertEquals(ZoneTier.classify(curve, 30.0, 1.0), ZoneTier.classify(curve, 30.0, 0.5),
                    "a power under the floor reads exactly as the floor does");
        }
    }

    @Test
    void aPowerAtOrBelowTheOriginReadsAsTheFreshCharacter() {
        // 0, a negative sentinel and the curve's own origin are one reading: the weakest fight the curve
        // expresses, so an unknown character never gets an EASY by accident.
        for (DifficultyStatCurve curve : List.of(LINE, BENT)) {
            double atOrigin = ZoneTier.threatRatio(curve, 30.0, 1.0);
            assertEquals(atOrigin, ZoneTier.threatRatio(curve, 30.0, 0.0), 0.0, "power 0 reads at the origin");
            assertEquals(atOrigin, ZoneTier.threatRatio(curve, 30.0, -5.0), 0.0, "a negative power reads at the origin");
            assertEquals(ZoneTier.DEADLY, ZoneTier.classify(curve, 30.0, 0.0),
                    "difficulty 30 is deadly for a character the mod knows nothing about on " + curve);
            assertEquals(ZoneTier.FAIR, ZoneTier.classify(curve, 1.0, 0.0), "and difficulty 1 is its matched fight");
        }
    }

    @Test
    void aRailedCurveIsReadRailedExactlyAsTheFoldReadsIt() {
        // Past the rail the mob really does hit the same, so two zones past it read alike, and a zone past
        // the rail against a power below it reads the rail's own lead and no more.
        double railedAt = RAILED.maxOutDamageMult();
        assertEquals(railedAt, RAILED.outFactor(300.0), 0.0, "the fixture rails inside the band");
        assertEquals(railedAt, RAILED.outFactor(200.0), 0.0);
        assertEquals(ZoneTier.FAIR, ZoneTier.classify(RAILED, 300.0, 200.0), "both sides pinned: the same hit");
        assertEquals(railedAt / RAILED.outFactor(50.0), ZoneTier.threatRatio(RAILED, 300.0, 50.0), 0.0,
                "the difficulty side pinned: the rail's lead, never more");
        assertEquals(ZoneTier.threatRatio(RAILED, 300.0, 50.0), ZoneTier.threatRatio(RAILED, 900.0, 50.0), 0.0,
                "a zone three times further past the rail reads the same lead");
        // Below the rail the railed curve is the bent curve, so the word is the same one.
        assertEquals(ZoneTier.classify(BENT, 25.0, 10.0), ZoneTier.classify(RAILED, 25.0, 10.0));
    }

    @Test
    void theIdentityCurveAndANonFiniteRatioBothReadAsMatched() {
        // A flat damage axis: every zone hits alike, so every zone is a matched fight.
        assertEquals(ZoneTier.FAIR, ZoneTier.classify(DifficultyStatCurve.NONE, 200.0, 1.0));
        assertEquals(ZoneTier.FAIR, ZoneTier.classify(DifficultyStatCurve.NONE, 1.0, 200.0));
        // Corrupt input carries no information and must not fall through to DEADLY.
        assertEquals(ZoneTier.FAIR, ZoneTier.fromThreatRatio(Double.NaN));
        assertEquals(ZoneTier.FAIR, ZoneTier.fromThreatRatio(Double.POSITIVE_INFINITY));
        assertEquals(ZoneTier.FAIR, ZoneTier.classify(BENT, Double.NaN, 40.0));
        assertEquals(ZoneTier.FAIR, ZoneTier.classify(BENT, 40.0, Double.NaN));
    }

    @Test
    void langKeysFollowTheConvention() {
        for (ZoneTier tier : ZoneTier.values()) {
            assertTrue(tier.langKey().startsWith("mmomobscaling.hud.zone.tier."),
                    tier + " key follows the mmomobscaling.hud.zone.tier.* convention");
            assertTrue(tier.colorHex().matches("#[0-9a-fA-F]{6}"),
                    tier + " carries a six-digit hex TextColor");
        }
    }
}
