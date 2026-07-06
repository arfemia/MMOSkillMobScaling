package com.ziggfreed.mmomobscaling.scaling;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.variant.Variant;

/**
 * Folds a rolled {@link Rarity} + an optional {@link Variant} overlay + their {@link Affix}es into the single
 * frozen {@link MobScaleResult} at spawn. A configurable {@link DifficultyStatCurve} scales EVERY hostile mob
 * off the resolved difficulty (read as an effective content level, 1-200) FIRST; rarity + affix contributions
 * then stack on top, and a VARIANT's multipliers stack MULTIPLICATIVELY on top of that (an independent overlay
 * axis - "Horrific Epic" = epic base * horrific overlay). Affix contributions are ADDITIVE deltas on the
 * damage/HP axes (predictable for owners, no runaway nested multiply) summed into per-axis base multipliers,
 * the curve factor multiplies those bases, the variant factor multiplies again, then every axis is CLAMPED to
 * the safety caps (the plan's balance decision):
 *
 * <ul>
 *   <li>HP mult: {@code curve.hpFactor(d) * (rarity.hpMult + sum(affix.hpDelta))}, clamped to
 *       {@code [MIN_HP_MULT, curve.maxHpMult]} (NONE = legacy ceiling 4.5).</li>
 *   <li>Outgoing dmg: {@code curve.outFactor(d) * (rarity.out + sum(affix.outDelta))}, clamped
 *       {@code [OUT_DMG_MIN, curve.maxOutDamageMult]} (NONE = legacy ceiling 3.0).</li>
 *   <li>Incoming dmg (tankiness): {@code curve.inFactor(d) * (rarity.in + sum(affix.inDelta))}, clamped
 *       {@code [curve.minInDamageMult, IN_DMG_MAX]} (NONE = legacy floor 0.5, a x2 effective-HP ceiling;
 *       the mitigation from an Armored affix is native {@code DamageResistance}, NOT folded here).</li>
 *   <li>Loot: {@code rarity.loot * (1 + sum(affix.lootBonus))}, clamped {@code [0.5, 3.0]} (curve-free).</li>
 *   <li>XP: {@code rarity.xp} (affixes do not modify XP in the MVP).</li>
 * </ul>
 *
 * Pure, deterministic, engine-free.
 */
public final class MobScaleFold {

    /** Post-fold hard ceiling on the HP multiplier (per the plan's balance caps). */
    public static final double MAX_HEALTH_MULT = 4.5;
    /** Post-fold hard floor on the HP multiplier (a curve/rarity can never shrink a mob below this). */
    public static final double MIN_HP_MULT = 0.1;
    public static final double IN_DMG_MIN = 0.5;
    public static final double IN_DMG_MAX = 1.0;
    public static final double OUT_DMG_MIN = 0.5;
    public static final double OUT_DMG_MAX = 3.0;
    public static final double LOOT_MULT_MIN = 0.5;
    public static final double LOOT_MULT_MAX = 3.0;

    private MobScaleFold() {
    }

    /**
     * A configurable difficulty-to-stat curve applied to EVERY hostile mob (plain included). Difficulty is now
     * read as an effective content level (1-200) and drives three factors that scale HP, outgoing damage, and
     * tankiness (incoming-damage reduction). The factors are the FIRST layer of the fold; a rolled rarity + its
     * affixes then stack multiplicatively on top (see {@link MobScaleFold#fold}).
     *
     * <p>{@link #NONE} is the identity curve (every factor collapses to 1.0 and the caps fall back to the legacy
     * {@code 4.5 / 3.0 / 0.5}), so folding through {@code NONE} reproduces the legacy rarity-only behavior byte
     * for byte.
     *
     * @param hpPerPoint          added HP-factor slope per difficulty point above 1.
     * @param outPerPoint         added outgoing-damage-factor slope per difficulty point above 1.
     * @param inReductionPerPoint incoming-damage reduction per difficulty point above 1 (higher = tankier).
     * @param maxHpMult           post-curve HP ceiling (also the {@code fold} HP clamp upper bound).
     * @param maxOutDamageMult    post-curve outgoing-damage ceiling (also the {@code fold} out clamp upper bound).
     * @param minInDamageMult     incoming-damage floor (also the {@code fold} in clamp lower bound).
     */
    public record DifficultyStatCurve(
            double hpPerPoint, double outPerPoint, double inReductionPerPoint,
            double maxHpMult, double maxOutDamageMult, double minInDamageMult) {

        /** The identity curve: all factors 1.0 with the legacy caps, so the fold stays rarity-only. */
        public static final DifficultyStatCurve NONE =
                new DifficultyStatCurve(0.0, 0.0, 0.0, MAX_HEALTH_MULT, OUT_DMG_MAX, IN_DMG_MIN);

        /** HP multiplier from difficulty alone: {@code clamp(1 + (d-1)*hpPerPoint, 1, maxHpMult)}. */
        public double hpFactor(double difficulty) {
            double d = Math.max(1.0, difficulty);
            return clamp(1.0 + (d - 1.0) * hpPerPoint, 1.0, maxHpMult);
        }

        /** Outgoing-damage multiplier from difficulty alone: {@code clamp(1 + (d-1)*outPerPoint, 1, maxOutDamageMult)}. */
        public double outFactor(double difficulty) {
            double d = Math.max(1.0, difficulty);
            return clamp(1.0 + (d - 1.0) * outPerPoint, 1.0, maxOutDamageMult);
        }

        /** Incoming-damage multiplier (tankiness) from difficulty alone: {@code clamp(1 - (d-1)*inReductionPerPoint, minInDamageMult, 1)}. */
        public double inFactor(double difficulty) {
            double d = Math.max(1.0, difficulty);
            return clamp(1.0 - (d - 1.0) * inReductionPerPoint, minInDamageMult, 1.0);
        }
    }

    /**
     * Fold a plain mob (no rarity, no affixes): the curve factors ARE the mults (difficulty scales HP + outgoing
     * damage + tankiness), loot/XP stay 1.0, empty rarity/affix ids. The factors are already clamped within
     * their caps, so no extra clamp is needed.
     */
    @Nonnull
    public static MobScaleResult plain(double difficulty, byte scope, @Nonnull DifficultyStatCurve curve) {
        return new MobScaleResult((float) difficulty, "", "", new String[0],
                (float) curve.hpFactor(difficulty), (float) curve.outFactor(difficulty), (float) curve.inFactor(difficulty),
                1f, 1f, scope);
    }

    /**
     * Back-compat overload: fold a rarity + affixes with NO variant overlay. Delegates to
     * {@link #fold(Rarity, Variant, List, double, byte, DifficultyStatCurve)}.
     */
    @Nonnull
    public static MobScaleResult fold(@Nullable Rarity rarity, @Nonnull List<Affix> affixes, double difficulty,
            byte scope, @Nonnull DifficultyStatCurve curve) {
        return fold(rarity, null, affixes, difficulty, scope, curve);
    }

    /**
     * Fold a rolled rarity + an optional {@code variant} overlay + their combined affixes into the frozen
     * result, with the difficulty curve applied first. Both {@code null} (and no affixes) yields the plain
     * (curve-only) result. Rarity + affix deltas sum into per-axis base multipliers, the curve factor
     * multiplies each base, the VARIANT factor multiplies again (multiplicative overlay), then every axis is
     * clamped to the curve's caps. A {@code null} rarity with a non-null variant still folds (base = 1.0, so a
     * plain mob can still carry a variant: "Horrific Spider").
     */
    @Nonnull
    public static MobScaleResult fold(@Nullable Rarity rarity, @Nullable Variant variant,
            @Nonnull List<Affix> affixes, double difficulty, byte scope, @Nonnull DifficultyStatCurve curve) {
        if (rarity == null && variant == null) {
            return plain(difficulty, scope, curve);
        }
        double hpBase = rarity != null ? rarity.hpMult() : 1.0;
        double outBase = rarity != null ? rarity.outDamageMult() : 1.0;
        double inBase = rarity != null ? rarity.inDamageMult() : 1.0;
        double lootBase = rarity != null ? rarity.lootMult() : 1.0;
        double xp = rarity != null ? rarity.xpMult() : 1.0;
        double lootBonus = 0.0;
        for (Affix a : affixes) {
            hpBase += a.hpDelta();
            outBase += a.outDamageDelta();
            inBase += a.inDamageDelta();
            lootBonus += a.lootBonus();
        }
        // Variant multipliers stack MULTIPLICATIVELY on top of the base rarity+affix layer (1.0 = no variant).
        double vHp = variant != null ? variant.hpMult() : 1.0;
        double vOut = variant != null ? variant.outDamageMult() : 1.0;
        double vIn = variant != null ? variant.inDamageMult() : 1.0;
        double vLoot = variant != null ? variant.lootMult() : 1.0;
        double vXp = variant != null ? variant.xpMult() : 1.0;

        double hp = clamp(curve.hpFactor(difficulty) * hpBase * vHp, MIN_HP_MULT, curve.maxHpMult());
        double out = clamp(curve.outFactor(difficulty) * outBase * vOut, OUT_DMG_MIN, curve.maxOutDamageMult());
        double in = clamp(curve.inFactor(difficulty) * inBase * vIn, curve.minInDamageMult(), IN_DMG_MAX);
        double loot = clamp(lootBase * (1.0 + lootBonus) * vLoot, LOOT_MULT_MIN, LOOT_MULT_MAX);
        xp = xp * vXp;

        String[] affixIds = new String[affixes.size()];
        for (int i = 0; i < affixes.size(); i++) {
            affixIds[i] = affixes.get(i).id();
        }
        String rarityId = rarity != null ? rarity.id() : "";
        String variantId = variant != null ? variant.id() : "";
        return new MobScaleResult((float) difficulty, rarityId, variantId, affixIds,
                (float) hp, (float) out, (float) in, (float) loot, (float) xp, scope);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
