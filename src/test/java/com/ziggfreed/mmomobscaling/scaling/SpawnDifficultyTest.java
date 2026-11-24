package com.ziggfreed.mmomobscaling.scaling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.scaling.AggregationMode;
import com.ziggfreed.common.scaling.PowerAggregation;
import com.ziggfreed.common.scaling.ScalingContext;
import com.ziggfreed.common.scaling.ScalingEngine;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.scaling.MobScaleFold.Clamps;
import com.ziggfreed.mmomobscaling.scaling.MobScaleFold.DifficultyStatCurve;

/**
 * The spawn's two numbers (R478, R1198): the place sets a mob's health and damage, the place plus the nearby
 * group's power sets what it rolls, and {@code GroupDeltaStatShare} is the one knob that lets the group's move
 * reach the numbers too (0 none, 1 all of it, between a blend). Test-authored band, caps and curve; no shipped
 * number is restated.
 */
class SpawnDifficultyTest {

    private static final double BAND = 25.0;
    private static final double MIN_CAP = 1.0;
    private static final double MAX_CAP = 200.0;
    /** A bent test curve with rails far away, so the curve and never a rail decides every fold below. */
    private static final DifficultyStatCurve CURVE = new DifficultyStatCurve(0.05, 0.85, 0.03, 1.4, 1_000.0, 1_000.0);
    private static final Clamps RAILS = new Clamps(0.1, 1.0, 0.5, 0.5, 4.0);

    private static SpawnDifficulty resolve(double place, double regionPower, double share) {
        return SpawnDifficulty.resolve(place, regionPower, AggregationMode.AVERAGE, BAND, MIN_CAP, MAX_CAP, true,
                share);
    }

    /** The single group resolve: one number for health, damage and the roll alike, which share 1 must equal. */
    private static double groupResolveAsOneNumber(double place, double regionPower) {
        double scaled = ScalingEngine.resolve(ScalingContext.openWorld(place, regionPower, AggregationMode.AVERAGE),
                BAND, MIN_CAP, MAX_CAP);
        return Math.max(scaled, place);
    }

    @Test
    void atShareZeroHealthAndDamageReadThePlaceAndTheGroupMovesOnlyTheRoll() {
        SpawnDifficulty at = resolve(40.0, 90.0, 0.0);
        assertEquals(40.0, at.place(), 1e-12);
        assertEquals(40.0, at.stat(), 1e-12, "health and damage come from the place alone");
        assertEquals(groupResolveAsOneNumber(40.0, 90.0), at.roll(), 1e-12,
                "the roll is the group resolve: the place plus the band-clamped delta");
        assertEquals(BAND, at.groupDelta(), 1e-12, "a group far above the place fills the band");
        assertEquals(0.0, at.statDelta(), 1e-12);
    }

    @Test
    void atShareOneHealthAndDamageReadTheRollExactlyAsTheSingleResolve() {
        for (double power : new double[] {10.0, 55.0, 64.9, 300.0}) {
            SpawnDifficulty at = resolve(40.0, power, 1.0);
            double oneNumber = groupResolveAsOneNumber(40.0, power);
            assertEquals(oneNumber, at.stat(), 0.0, "share 1 is the single resolve at power " + power);
            assertEquals(oneNumber, at.roll(), 0.0);
        }
    }

    @Test
    void atShareHalfHealthAndDamageTakeHalfTheGroupsMove() {
        SpawnDifficulty at = resolve(40.0, 90.0, 0.5);
        assertEquals(40.0 + 0.5 * at.groupDelta(), at.stat(), 1e-12);
        assertTrue(at.place() < at.stat() && at.stat() < at.roll(), "a blend sits between the place and the roll");
    }

    @Test
    void theShareIsHeldToZeroThroughOneAndANonNumberReadsAsZero() {
        assertEquals(0.0, SpawnDifficulty.share(-0.4), 0.0);
        assertEquals(0.0, SpawnDifficulty.share(Double.NaN), 0.0);
        assertEquals(1.0, SpawnDifficulty.share(3.0), 0.0);
        assertEquals(0.25, SpawnDifficulty.share(0.25), 0.0);
        assertEquals(40.0, resolve(40.0, 90.0, -1.0).stat(), 1e-12, "a negative share never lowers health below the place");
        assertEquals(resolve(40.0, 90.0, 1.0).stat(), resolve(40.0, 90.0, 7.0).stat(), 1e-12,
                "a share past one never lifts health past the roll");
    }

    @Test
    void withNoGroupThePlaceIsAllThreeNumbers() {
        SpawnDifficulty at = SpawnDifficulty.placeOnly(33.0);
        assertEquals(33.0, at.place(), 0.0);
        assertEquals(33.0, at.stat(), 0.0);
        assertEquals(33.0, at.roll(), 0.0);
    }

    @Test
    void theFoldReadsTheCurveAtTheStatDifficultyAndCarriesTheRoll() {
        Rarity epic = new Rarity("epic", "", 25, 25, 1.8, 1.5, 2, null, List.of("*"));
        SpawnDifficulty at = resolve(30.0, 80.0, 0.0);
        MobScaleResult rolled = MobScaleFold.fold(epic, null, List.of(), at, MobScaleResult.SCOPE_HOSTILE, CURVE, RAILS);
        MobScaleResult atThePlace = MobScaleFold.fold(epic, null, List.of(), 30.0, MobScaleResult.SCOPE_HOSTILE,
                CURVE, RAILS);
        assertEquals(atThePlace.hpMult(), rolled.hpMult(), 0.0, "the health a place gives does not move with the group");
        assertEquals(atThePlace.outDmgMult(), rolled.outDmgMult(), 0.0);
        assertEquals((float) at.stat(), rolled.difficulty(), 0.0f, "the result carries what health and damage read");
        assertEquals((float) at.roll(), rolled.rollDifficulty(), 0.0f, "and what its rarity and affixes rolled at");

        MobScaleResult plain = MobScaleFold.fold(null, null, List.of(), at, MobScaleResult.SCOPE_HOSTILE, CURVE, RAILS);
        assertEquals((float) at.roll(), plain.rollDifficulty(), 0.0f,
                "a mob whose roll came up plain still carries the roll its casters gate on");
    }

    /**
     * The design's party case: a strong player (power 104.9) and a newer one (power 30) share one region cell at
     * a floor-20 spot. The newer player is never hit harder because of the partner: every mob, plain or rare,
     * has the same health and the same hit with the partner as without, while the pair's rolls read higher, so
     * they meet rarer mobs.
     */
    @Test
    void aStrongPartnerNeverMakesANewPlayersMobsHitHarderOnlyRarer() {
        double place = 20.0;
        double alonePower = PowerAggregation.fold(new double[] {30.0}, AggregationMode.AVERAGE);
        double pairPower = PowerAggregation.fold(new double[] {104.9, 30.0}, AggregationMode.AVERAGE);
        SpawnDifficulty alone = resolve(place, alonePower, 0.0);
        SpawnDifficulty pair = resolve(place, pairPower, 0.0);

        assertEquals(alone.stat(), pair.stat(), 0.0, "health and damage read the same difficulty with the partner");
        assertEquals(place, pair.stat(), 0.0, "and it is the place's");
        assertTrue(pair.roll() > alone.roll(), "the pair's rolls read higher, so rarer mobs, affixes and casters come sooner");

        List<Rarity> tiers = List.of(
                new Rarity("rare", "", 70, 5, 1.35, 1.2, 1, null, List.of("*")),
                new Rarity("epic", "", 25, 25, 1.8, 1.5, 2, null, List.of("*")),
                new Rarity("legendary", "", 5, 50, 2.4, 2.0, 3, null, List.of("*")));
        MobScaleResult plainAlone = MobScaleFold.fold(null, null, List.of(), alone, MobScaleResult.SCOPE_HOSTILE, CURVE, RAILS);
        MobScaleResult plainPair = MobScaleFold.fold(null, null, List.of(), pair, MobScaleResult.SCOPE_HOSTILE, CURVE, RAILS);
        assertEquals(plainAlone.outDmgMult(), plainPair.outDmgMult(), 0.0f, "a plain mob hits the newer player alike");
        assertEquals(plainAlone.hpMult(), plainPair.hpMult(), 0.0f);
        for (Rarity tier : tiers) {
            MobScaleResult a = MobScaleFold.fold(tier, null, List.of(), alone, MobScaleResult.SCOPE_HOSTILE, CURVE, RAILS);
            MobScaleResult p = MobScaleFold.fold(tier, null, List.of(), pair, MobScaleResult.SCOPE_HOSTILE, CURVE, RAILS);
            assertEquals(a.outDmgMult(), p.outDmgMult(), 0.0f, "a " + tier.id() + " hits alike with or without the partner");
            assertEquals(a.hpMult(), p.hpMult(), 0.0f, "a " + tier.id() + " is as tough with or without the partner");
        }

        // The owner's switch back: at share 1 the partner does raise the newer player's numbers, which is what
        // the shipped 0 keeps off.
        assertTrue(resolve(place, pairPower, 1.0).stat() > resolve(place, alonePower, 1.0).stat(),
                "at share 1 the group's power reaches health and damage again");
    }
}
