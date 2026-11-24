package com.ziggfreed.mmomobscaling.scaling;

import javax.annotation.Nonnull;

import com.ziggfreed.common.scaling.AggregationMode;
import com.ziggfreed.common.scaling.ScalingContext;
import com.ziggfreed.common.scaling.ScalingEngine;

/**
 * The two numbers a spawn resolves to (R478, R1198: "place sets the numbers, power makes them rarer"), plus
 * the one the owner may blend between them. Pure and engine-free, so the split is unit-tested on its own.
 *
 * <ul>
 *   <li>{@link #place}: the escalated floor of the spot (zone or biome floor plus the distance bonus,
 *       clamped to the caps). What the place itself is.</li>
 *   <li>{@link #roll}: the place plus the nearby group's band-clamped power delta, as ziggfreed-common's
 *       {@code ScalingEngine} resolves it (never below the place under {@code OnlyRaiseDifficulty}). What a
 *       mob's rarity, variant, affixes and caster abilities roll at, and what the rarity bands
 *       ({@code MinDifficulty}) gate on.</li>
 *   <li>{@link #stat}: what a mob's health and damage read: {@code place + share * (roll - place)}, the share
 *       being {@code OpenWorld.GroupDeltaStatShare}. At the shipped 0 it is the place, so a plain mob at a
 *       spot has the same health and hit whoever walks up; at 1 it is the roll exactly, the single resolve
 *       that health, damage and the roll once all read; anything between blends.</li>
 * </ul>
 *
 * A spawn with no group delta (a cold region, player scaling off, inside the protected ring) has all three
 * equal ({@link #placeOnly}).
 */
public record SpawnDifficulty(double place, double stat, double roll) {

    /** No group on top: the place is all three numbers. */
    @Nonnull
    public static SpawnDifficulty placeOnly(double place) {
        return new SpawnDifficulty(place, place, place);
    }

    /**
     * The group's resolve over {@code place}: the region's power folded by {@code mode} (one already-aggregated
     * scalar, so any live mode passes it through), its delta clamped to {@code bandWidth}, the result clamped to
     * the caps, held at or above the place when {@code onlyRaise}, then split by {@code statShare}.
     */
    @Nonnull
    public static SpawnDifficulty resolve(double place, double regionPower, @Nonnull AggregationMode mode,
            double bandWidth, double minCap, double maxCap, boolean onlyRaise, double statShare) {
        double scaled = ScalingEngine.resolve(ScalingContext.openWorld(place, regionPower, mode),
                bandWidth, minCap, maxCap);
        double roll = onlyRaise ? Math.max(scaled, place) : scaled;
        return split(place, roll, statShare);
    }

    /** {@code place} and {@code roll} as resolved, with health and damage taking {@code statShare} of the move. */
    @Nonnull
    public static SpawnDifficulty split(double place, double roll, double statShare) {
        double share = share(statShare);
        // The two ends exactly: the place at 0, the roll itself at 1 (never a rounding step off it).
        double stat = share >= 1.0 ? roll : share <= 0.0 ? place : place + share * (roll - place);
        return new SpawnDifficulty(place, stat, roll);
    }

    /** A stat share as the split reads it: clamped to {@code [0, 1]}, and a value that is not a number reads as 0. */
    public static double share(double statShare) {
        if (!(statShare > 0.0)) {
            return 0.0; // NaN, a negative and zero all read as "health and damage from the place alone"
        }
        return Math.min(1.0, statShare);
    }

    /** What the group adds to the roll over the place (negative only where a weaker group may soften it). */
    public double groupDelta() {
        return roll - place;
    }

    /** What the group adds to health and damage over the place (0 at the shipped share). */
    public double statDelta() {
        return stat - place;
    }
}
