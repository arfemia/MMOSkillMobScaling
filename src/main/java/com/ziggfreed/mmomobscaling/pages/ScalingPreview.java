package com.ziggfreed.mmomobscaling.pages;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.scaling.MobScaleFold;
import com.ziggfreed.mmomobscaling.scaling.MobScaleFold.Clamps;
import com.ziggfreed.mmomobscaling.scaling.MobScaleFold.DifficultyStatCurve;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;

/**
 * The arithmetic behind the admin page's preview column, pure and engine-free so it is unit-tested on
 * its own: what ONE mob folds to at a difficulty, and what every rarity tier folds to at a probed
 * difficulty (the ladder). Every figure comes out of the SAME {@link MobScaleFold} the spawn path
 * runs, fed the same {@link DifficultyStatCurve} and {@link Clamps} the fold reads, so the preview
 * can never show a number a spawn would not produce.
 *
 * <p>A {@link Sample} carries the three multipliers a spawn stamps ({@code hp}, {@code out}, {@code in}),
 * the TIME TO KILL relative to an unscaled mob ({@code hp / in}: how many times more hits the mob
 * absorbs, which is the one number the curve is derived to hold against a player's growth), and
 * whether either of the curve's own rails ({@code MaxEffectiveHpMult}, {@code MaxOutDamageMult}) is what
 * decided the figure at that difficulty. A railed figure is the case where turning a slope up changes
 * nothing, which an owner cannot see any other way.
 *
 * <p>The ladder reads each tier exactly as the fold does: at {@link MobScaleFold#curveDifficulty
 * difficulty times the tier's DifficultyMultiplier}, never re-clamped to the difficulty cap, so it shows
 * the ratio between tiers holding (or collapsing onto a rail) at the probed spot.
 */
public final class ScalingPreview {

    private ScalingPreview() {
    }

    /**
     * One mob's fold at one difficulty.
     *
     * @param difficulty  the difficulty the curve was read at ({@code dEff} for a tier)
     * @param hp          the visible health multiplier
     * @param out         the outgoing-damage multiplier
     * @param in          the incoming-damage multiplier (the share of a hit that lands)
     * @param timeToKill  {@code hp / in}: how many times longer the mob takes to kill than an unscaled one
     * @param hpRailed    whether {@code MaxEffectiveHpMult} decided the tank axis here
     * @param outRailed   whether {@code MaxOutDamageMult} decided the damage axis here
     */
    public record Sample(double difficulty, double hp, double out, double in, double timeToKill,
            boolean hpRailed, boolean outRailed) {

        /** True when either rail is what decided a figure at this difficulty. */
        public boolean railed() {
            return hpRailed || outRailed;
        }
    }

    /**
     * One rung of the ladder: the tier ({@code null} for the plain mob every tier is measured against)
     * and its fold at the probed difficulty times its multiplier.
     */
    public record Tier(@Nullable Rarity rarity, double difficultyMultiplier, @Nonnull Sample sample) {

        /** True for the plain reference rung. */
        public boolean plain() {
            return rarity == null;
        }
    }

    /**
     * A plain mob (no rarity, variant or affix) at {@code difficulty}, through the fold's own
     * {@link MobScaleFold#plain}, which takes no {@link Clamps}: a plain mob's factors are the curve's
     * own, already inside its rails. A ladder rung is different, see {@link #ladder}: it goes through
     * {@link MobScaleFold#fold} with the clamps, exactly as a spawn does.
     */
    @Nonnull
    public static Sample sample(@Nonnull DifficultyStatCurve curve, double difficulty) {
        return sample(MobScaleFold.plain(difficulty, MobScaleResult.SCOPE_HOSTILE, curve), curve, difficulty);
    }

    /**
     * The rarity ladder at {@code difficulty}: the plain mob first, then every tier from weakest to
     * strongest by {@link Rarity#compareStrength}, each folded through {@link MobScaleFold#fold} with
     * no variant and no affixes against {@code curve} and {@code clamps}. A tier the roster does not
     * carry does not appear; a tier a pack adds does, in its place on the ladder.
     */
    @Nonnull
    public static List<Tier> ladder(@Nonnull DifficultyStatCurve curve, @Nonnull Clamps clamps,
            @Nonnull Collection<Rarity> rarities, double difficulty) {
        List<Rarity> sorted = new ArrayList<>();
        for (Rarity r : rarities) {
            if (r != null) {
                sorted.add(r);
            }
        }
        sorted.sort(Rarity::compareStrength);
        List<Tier> out = new ArrayList<>(sorted.size() + 1);
        out.add(new Tier(null, 1.0, sample(curve, difficulty)));
        for (Rarity r : sorted) {
            MobScaleResult result = MobScaleFold.fold(r, null, List.of(), difficulty,
                    MobScaleResult.SCOPE_HOSTILE, curve, clamps);
            double dEff = MobScaleFold.curveDifficulty(difficulty, r, null);
            out.add(new Tier(r, r.difficultyMultiplier(), sample(result, curve, dEff)));
        }
        return List.copyOf(out);
    }

    /** The fold's stamped result at {@code dEff}, read back into a {@link Sample} with the rails asked of the curve. */
    @Nonnull
    private static Sample sample(@Nonnull MobScaleResult result, @Nonnull DifficultyStatCurve curve, double dEff) {
        double hp = result.hpMult();
        double in = result.inDmgMult();
        double timeToKill = in > 0.0 ? hp / in : hp;
        return new Sample(dEff, hp, result.outDmgMult(), in, timeToKill,
                curve.effectiveHpRailed(dEff), curve.outRailed(dEff));
    }
}
