package com.ziggfreed.mmomobscaling.scaling;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.variant.Variant;

/**
 * Folds a rolled {@link Rarity} + an optional {@link Variant} overlay + their {@link Affix}es into the single
 * frozen {@link MobScaleResult} at spawn. Pure, deterministic, engine-free.
 *
 * <p><b>Rarity and variant are multipliers on DIFFICULTY, not on stats.</b> The curve is evaluated at
 * {@code dEff = difficulty * rarity.difficultyMultiplier * variant.difficultyMultiplier}, so a Legendary at
 * difficulty 30 is folded exactly like a plain mob at difficulty 72, and the ratio between two tiers holds
 * across the whole band instead of collapsing wherever a per-stat clamp binds. {@code dEff} is deliberately
 * NOT clamped to {@code Difficulty.MaxCap}: that cap bounds the ZONE difficulty a spawn resolves to (the
 * number the HUD, the inspector, the roll gates and {@link MobScaleResult#difficulty()} all carry), and the
 * curve's own rails bound the fold. Re-clamping {@code dEff} is what would fold every tier onto one number
 * at the top of the band.
 *
 * <p><b>One effective-HP curve, split geometrically.</b> The tank axis is ONE slope,
 * {@code ehp = clamp(1 + (dEff - 1) * EffectiveHpPerPoint, 1, MaxEffectiveHpMult)}, shared between the
 * visible health bar and the invisible incoming-damage multiplier by {@code VisibleHpShare}:
 * {@code hp = ehp ^ share} and {@code in = ehp ^ (share - 1)}, so {@code hp / in == ehp} exactly for any
 * share in {@code [0, 1]}. A share of 1.0 puts all the toughness on the bar (in stays 1.0), 0.5 splits it
 * evenly (both {@code sqrt(ehp)}). There is no separate incoming-damage slope and no pole to floor.
 *
 * <p><b>The damage axis is a power curve, not a line.</b>
 * {@code out = clamp(1 + OutDamageScale * (dEff - 1) ^ OutDamageShape, 1, MaxOutDamageMult)}. A shape of
 * 1.0 is the straight line {@code 1 + (dEff - 1) * scale}, so the scale is then exactly a per-point slope;
 * a shape above 1.0 bends the curve upward, growing gently over the early difficulties and steeply over
 * the late ones. The tank axis stays straight on purpose: a player's toughness grows faster than their
 * damage over a progression, and it does so along a curve, so the fair mob's hit must follow one too
 * while the fair mob's health does not.
 *
 * <p><b>Affix deltas multiply the curve.</b> {@code (1 + sum(FoldDeltas.Hp))} scales {@code hp},
 * {@code (1 + sum(FoldDeltas.OutDamage))} scales {@code out}, {@code (1 + sum(FoldDeltas.InDamage))} scales
 * {@code in}, and {@code (1 + sum(LootBonus))} scales the loot pass count. Then the safety rails
 * ({@link Clamps}: the floors and ceilings that are not the curve's own shape) clamp each axis, and LAST the
 * composite rail is enforced: a mob's effective HP as the player experiences it is
 * {@code hp / (in * (1 - resistance))}, {@code resistance} being the sum of the affixes' declared
 * {@code FoldDeltas.ResistancePercent} mirrors of their native {@code DamageResistance}; when that product
 * exceeds {@code MaxEffectiveHpMult} the correction is put on {@code in} (raised), never on {@code hp}, because
 * {@code hp} is what the inspector shows and the health bar reflects while {@code in} is invisible. The
 * composite rail is the last word, so it may lift {@code in} past {@code Clamps.MaxInDamageMult} in the
 * extreme case where the visible HP alone already exceeds the rail.
 *
 * <p>Loot: {@code rarity.loot * variant.loot * (1 + sum(affix.lootBonus))}, clamped to
 * {@code [MinLootMult, MaxLootMult]}. XP: {@code rarity.xp * variant.xp}, unclamped and affix-free.
 */
public final class MobScaleFold {

    private MobScaleFold() {
    }

    /**
     * The difficulty-to-stat curve applied to EVERY hostile mob: the one effective-HP slope with its visible
     * share, the outgoing-damage scale and shape, and the two ceilings that are the curve's own range. Fed
     * from {@code Difficulty.StatCurve}; a rarity or variant reaches it only through the difficulty it is
     * evaluated at (see {@link MobScaleFold}).
     *
     * <p>{@link #NONE} is the genuine IDENTITY: every factor 1.0 at every difficulty, with no rail below 1.0.
     * It is the broken-jar fail-safe and the residue-cleanup stamp, and it carries no tuning of its own.
     *
     * @param effectiveHpPerPoint effective-HP slope per difficulty point above 1 (the ONE tank slope).
     * @param visibleHpShare      the exponent share of the effective HP that shows on the health bar, in [0, 1].
     * @param outDamageScale      the coefficient on the shaped difficulty distance of the outgoing-damage
     *                            curve; at a shape of 1.0 it is exactly the per-point slope.
     * @param outDamageShape      the exponent on {@code (dEff - 1)} in the outgoing-damage curve: 1.0 is a
     *                            straight line, above 1.0 bends it upward. A non-positive shape reads as 1.0.
     * @param maxEffectiveHpMult  the composite rail on {@code hp / in} (and on the curve's own {@code ehp}).
     * @param maxOutDamageMult    the ceiling on the outgoing-damage multiplier.
     */
    public record DifficultyStatCurve(
            double effectiveHpPerPoint, double visibleHpShare, double outDamageScale, double outDamageShape,
            double maxEffectiveHpMult, double maxOutDamageMult) {

        /** The identity curve: every factor 1.0 at every difficulty, no tuning. */
        public static final DifficultyStatCurve NONE = new DifficultyStatCurve(0.0, 1.0, 0.0, 1.0, 1.0, 1.0);

        public DifficultyStatCurve {
            visibleHpShare = clamp(visibleHpShare, 0.0, 1.0);
            outDamageShape = outDamageShape > 0.0 ? outDamageShape : 1.0;
        }

        /** The effective-HP multiplier from difficulty alone: {@code clamp(1 + (d-1)*slope, 1, maxEffectiveHpMult)}. */
        public double effectiveHp(double difficulty) {
            return clamp(unrailedEffectiveHp(difficulty), 1.0, Math.max(1.0, maxEffectiveHpMult));
        }

        /**
         * Whether {@code maxEffectiveHpMult} is what decides the tank axis at {@code difficulty}: the
         * line {@code 1 + (d - 1) * slope} has climbed past the rail, so every difficulty from here up
         * folds to the same toughness and a steeper slope changes nothing here. What the admin preview
         * marks so an owner can see a ceiling holding a number down.
         */
        public boolean effectiveHpRailed(double difficulty) {
            return unrailedEffectiveHp(difficulty) > Math.max(1.0, maxEffectiveHpMult);
        }

        /** The tank line before its rail: {@code 1 + (d - 1) * slope}, floored at difficulty 1. */
        private double unrailedEffectiveHp(double difficulty) {
            double d = Math.max(1.0, difficulty);
            return 1.0 + (d - 1.0) * effectiveHpPerPoint;
        }

        /** The visible HP multiplier from difficulty alone: {@code ehp ^ visibleHpShare}. */
        public double hpFactor(double difficulty) {
            return Math.pow(effectiveHp(difficulty), visibleHpShare);
        }

        /** The incoming-damage multiplier from difficulty alone: {@code ehp ^ (visibleHpShare - 1)}, at most 1.0. */
        public double inFactor(double difficulty) {
            return Math.pow(effectiveHp(difficulty), visibleHpShare - 1.0);
        }

        /**
         * The outgoing-damage multiplier from difficulty alone:
         * {@code clamp(1 + scale * (d - 1) ^ shape, 1, maxOutDamageMult)}. At difficulty 1 the shaped distance
         * is 0 whatever the shape, so the curve starts at exactly 1.0.
         */
        public double outFactor(double difficulty) {
            return clamp(unrailedOut(difficulty), 1.0, Math.max(1.0, maxOutDamageMult));
        }

        /**
         * Whether {@code maxOutDamageMult} is what decides the damage axis at {@code difficulty}: the
         * curve has climbed past the rail, so every difficulty from here up hits alike and a larger
         * scale or shape changes nothing here. The damage-axis twin of {@link #effectiveHpRailed}.
         */
        public boolean outRailed(double difficulty) {
            return unrailedOut(difficulty) > Math.max(1.0, maxOutDamageMult);
        }

        /** The damage curve before its rail: {@code 1 + scale * (d - 1) ^ shape}, floored at difficulty 1. */
        private double unrailedOut(double difficulty) {
            double d = Math.max(1.0, difficulty);
            return 1.0 + outDamageScale * Math.pow(d - 1.0, outDamageShape);
        }

    }

    /**
     * The safety rails that are NOT the curve's shape ({@code Difficulty.Clamps}): the floor under the
     * visible HP multiplier, the ceiling on the incoming-damage multiplier, the floor under the outgoing
     * multiplier, and the loot pass-count band. Applied per axis after the affix deltas; the composite rail
     * in {@link DifficultyStatCurve#maxEffectiveHpMult()} is enforced after them.
     *
     * <p>{@link #NONE} is the rail that never binds (the identity for a clamp), the broken-jar fail-safe.
     */
    public record Clamps(double minHpMult, double maxInDamageMult, double minOutDamageMult,
            double minLootMult, double maxLootMult) {

        /** No rail on any axis. */
        public static final Clamps NONE = new Clamps(0.0, Double.POSITIVE_INFINITY, 0.0, 0.0, Double.POSITIVE_INFINITY);
    }

    /**
     * Fold a plain mob (no rarity, no variant, no affixes): the curve factors at the zone difficulty ARE the
     * mults, loot and XP stay 1.0, empty ids. The factors already sit inside the curve's own rails, so no
     * clamp is needed.
     */
    @Nonnull
    public static MobScaleResult plain(double difficulty, byte scope, @Nonnull DifficultyStatCurve curve) {
        return new MobScaleResult((float) difficulty, "", "", new String[0],
                (float) curve.hpFactor(difficulty), (float) curve.outFactor(difficulty), (float) curve.inFactor(difficulty),
                1f, 1f, scope);
    }

    /**
     * Fold a rolled rarity + an optional {@code variant} overlay + their combined affixes into the frozen
     * result. {@code difficulty} is the ZONE difficulty the spawn resolved to and is what the result carries;
     * the curve is evaluated at {@link #curveDifficulty}. Both {@code null} and no affixes yields the
     * plain result. A {@code null} rarity with a variant still folds (a plain mob can carry a variant:
     * "Horrific Spider").
     */
    @Nonnull
    public static MobScaleResult fold(@Nullable Rarity rarity, @Nullable Variant variant,
            @Nonnull List<Affix> affixes, double difficulty, byte scope, @Nonnull DifficultyStatCurve curve,
            @Nonnull Clamps clamps) {
        if (rarity == null && variant == null && affixes.isEmpty()) {
            return plain(difficulty, scope, curve);
        }
        double dEff = curveDifficulty(difficulty, rarity, variant);
        double hpScale = 1.0;
        double outScale = 1.0;
        double inScale = 1.0;
        double lootBonus = 0.0;
        double resistance = 0.0;
        for (Affix a : affixes) {
            hpScale += a.hpDelta();
            outScale += a.outDamageDelta();
            inScale += a.inDamageDelta();
            lootBonus += a.lootBonus();
            resistance += a.resistancePercent();
        }
        double hp = Math.max(clamps.minHpMult(), curve.hpFactor(dEff) * hpScale);
        double out = clamp(curve.outFactor(dEff) * outScale, clamps.minOutDamageMult(), Math.max(1.0, curve.maxOutDamageMult()));
        double in = Math.min(clamps.maxInDamageMult(), curve.inFactor(dEff) * inScale);
        in = boundEffectiveHp(hp, in, resistance, curve.maxEffectiveHpMult());

        double lootBase = (rarity != null ? rarity.lootMult() : 1.0) * (variant != null ? variant.lootMult() : 1.0);
        double loot = clamp(lootBase * (1.0 + lootBonus), clamps.minLootMult(), clamps.maxLootMult());
        double xp = (rarity != null ? rarity.xpMult() : 1.0) * (variant != null ? variant.xpMult() : 1.0);

        String[] affixIds = new String[affixes.size()];
        for (int i = 0; i < affixes.size(); i++) {
            affixIds[i] = affixes.get(i).id();
        }
        String rarityId = rarity != null ? rarity.id() : "";
        String variantId = variant != null ? variant.id() : "";
        return new MobScaleResult((float) difficulty, rarityId, variantId, affixIds,
                (float) hp, (float) out, (float) in, (float) loot, (float) xp, scope);
    }

    /**
     * The difficulty the curve is READ at for this rarity + variant pairing ({@code dEff}):
     * {@code difficulty * rarity.difficultyMultiplier * variant.difficultyMultiplier}, never below 1 and
     * never clamped to the zone cap (see the class javadoc for why). Distinct from the SPOT difficulty a
     * spawn resolves to ({@code MobScalingSpawnHook.resolveSpawnScaling}: floor + escalation + group
     * delta, capped), which is the {@code difficulty} passed in and the number every read-out carries.
     */
    public static double curveDifficulty(double difficulty, @Nullable Rarity rarity, @Nullable Variant variant) {
        double d = Math.max(1.0, difficulty);
        if (rarity != null) {
            d *= Math.max(0.0, rarity.difficultyMultiplier());
        }
        if (variant != null) {
            d *= Math.max(0.0, variant.difficultyMultiplier());
        }
        return Math.max(1.0, d);
    }

    /**
     * The composite rail: the incoming-damage multiplier that keeps {@code hp / (in * (1 - resistance))} at or
     * under {@code maxEffectiveHpMult}. Returns {@code in} unchanged when the product is already within the
     * rail, else the raised value; {@code hp} is never touched. A resistance at or above 1.0 (outright
     * immunity by the native effect) cannot be bounded from here and is left to the effect's own validation.
     */
    static double boundEffectiveHp(double hp, double in, double resistance, double maxEffectiveHpMult) {
        double rail = Math.max(1.0, maxEffectiveHpMult);
        double surviving = 1.0 - clamp(resistance, 0.0, 1.0);
        if (surviving <= 0.0 || in <= 0.0) {
            return in;
        }
        double effective = hp / (in * surviving);
        if (effective <= rail) {
            return in;
        }
        return hp / (rail * surviving);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
