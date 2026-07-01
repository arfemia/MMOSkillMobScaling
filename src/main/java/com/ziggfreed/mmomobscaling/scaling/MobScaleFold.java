package com.ziggfreed.mmomobscaling.scaling;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.rarity.Rarity;

/**
 * Folds a rolled {@link Rarity} + its {@link Affix}es into the single frozen {@link MobScaleResult} at spawn.
 * Affix contributions are ADDITIVE deltas on the damage/HP axes (predictable for owners, no runaway nested
 * multiply), then every axis is CLAMPED to the global safety caps (the plan's balance decision):
 *
 * <ul>
 *   <li>HP mult: {@code rarity.hpMult + sum(affix.hpDelta)}, clamped to {@code [0.1, MAX_HEALTH_MULT]}.</li>
 *   <li>Outgoing dmg: {@code rarity.out + sum(affix.outDelta)}, clamped {@code [0.5, 3.0]}.</li>
 *   <li>Incoming dmg (tankiness): {@code rarity.in + sum(affix.inDelta)}, clamped {@code [0.5, 1.0]}
 *       (floor 0.5 = a x2 effective-HP ceiling; the mitigation from an Armored affix is native
 *       {@code DamageResistance}, NOT folded here).</li>
 *   <li>Loot: {@code rarity.loot * (1 + sum(affix.lootBonus))}, clamped {@code [0.5, 3.0]}.</li>
 *   <li>XP: {@code rarity.xp} (affixes do not modify XP in the MVP).</li>
 * </ul>
 *
 * Pure, deterministic, engine-free.
 */
public final class MobScaleFold {

    /** Post-fold hard ceiling on the HP multiplier (per the plan's balance caps). */
    public static final double MAX_HEALTH_MULT = 4.5;
    public static final double IN_DMG_MIN = 0.5;
    public static final double IN_DMG_MAX = 1.0;
    public static final double OUT_DMG_MIN = 0.5;
    public static final double OUT_DMG_MAX = 3.0;
    public static final double LOOT_MULT_MIN = 0.5;
    public static final double LOOT_MULT_MAX = 3.0;

    private MobScaleFold() {
    }

    /**
     * Fold a plain mob (no rarity, no affixes): all mults 1.0, empty rarity/affix ids.
     */
    @Nonnull
    public static MobScaleResult plain(double difficulty, byte scope) {
        return new MobScaleResult((float) difficulty, "", new String[0], 1f, 1f, 1f, 1f, 1f, scope);
    }

    /**
     * Fold a rolled rarity + affixes into the frozen result. A {@code null} rarity yields the plain result
     * (affixes are ignored without a rarity, since affix slots come from the rarity).
     */
    @Nonnull
    public static MobScaleResult fold(@Nullable Rarity rarity, @Nonnull List<Affix> affixes, double difficulty, byte scope) {
        if (rarity == null) {
            return plain(difficulty, scope);
        }
        double hp = rarity.hpMult();
        double out = rarity.outDamageMult();
        double in = rarity.inDamageMult();
        double loot = rarity.lootMult();
        double xp = rarity.xpMult();
        double lootBonus = 0.0;
        for (Affix a : affixes) {
            hp += a.hpDelta();
            out += a.outDamageDelta();
            in += a.inDamageDelta();
            lootBonus += a.lootBonus();
        }
        hp = clamp(hp, 0.1, MAX_HEALTH_MULT);
        out = clamp(out, OUT_DMG_MIN, OUT_DMG_MAX);
        in = clamp(in, IN_DMG_MIN, IN_DMG_MAX);
        loot = clamp(loot * (1.0 + lootBonus), LOOT_MULT_MIN, LOOT_MULT_MAX);

        String[] affixIds = new String[affixes.size()];
        for (int i = 0; i < affixes.size(); i++) {
            affixIds[i] = affixes.get(i).id();
        }
        return new MobScaleResult((float) difficulty, rarity.id(), affixIds,
                (float) hp, (float) out, (float) in, (float) loot, (float) xp, scope);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
