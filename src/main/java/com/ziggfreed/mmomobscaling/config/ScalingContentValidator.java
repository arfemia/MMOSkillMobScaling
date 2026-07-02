package com.ziggfreed.mmomobscaling.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.rarity.Rarity;

/**
 * Value-sanity validation over the FOLDED rarity/affix content (jar + pack + owner), run once per
 * {@code LoadedAssetsEvent} fold by {@code MobScalingAssetRegistrar} and logged as warnings; a finding
 * never blocks the load (bad content degrades, it does not kill the mod). Pure logic (no engine types),
 * unit-tested. EXISTENCE checks that need live asset maps stay at their consumption sites on purpose
 * (an unresolvable {@code AuraEffectId}/affix {@code EffectId} warns once in the effect-apply system, an
 * unresolvable {@code BonusDropList} warns once in the loot-drop system) - this class covers the pure
 * value ranges + shape rules those sites cannot see.
 */
public final class ScalingContentValidator {

    private ScalingContentValidator() {
    }

    /** Validate folded rarities; one human-readable finding per violation (empty = clean). */
    @Nonnull
    public static List<String> validateRarities(@Nonnull Collection<Rarity> rarities) {
        List<String> findings = new ArrayList<>();
        for (Rarity r : rarities) {
            String at = "rarity '" + r.id() + "'";
            if (r.weight() < 0) {
                findings.add(at + ": Weight must be >= 0 (0 = not rollable, force-only)");
            }
            if (r.minDifficulty() < 0) {
                findings.add(at + ": MinDifficulty must be >= 0");
            }
            if (r.hpMult() <= 0) {
                findings.add(at + ": HpMult must be > 0");
            }
            if (r.outDamageMult() <= 0 || r.inDamageMult() <= 0) {
                findings.add(at + ": damage multipliers must be > 0");
            }
            if (r.lootMult() < 0 || r.xpMult() < 0) {
                findings.add(at + ": LootMult/XpMult must be >= 0");
            }
            if (r.affixSlots() < 0) {
                findings.add(at + ": AffixSlots must be >= 0");
            }
        }
        return findings;
    }

    /** Validate folded affixes; one human-readable finding per violation (empty = clean). */
    @Nonnull
    public static List<String> validateAffixes(@Nonnull Collection<Affix> affixes) {
        List<String> findings = new ArrayList<>();
        for (Affix a : affixes) {
            String at = "affix '" + a.id() + "'";
            if (a.spawnWeight() < 0) {
                findings.add(at + ": Weight must be >= 0");
            }
            if (a.minDifficulty() < 0) {
                findings.add(at + ": MinDifficulty must be >= 0");
            }
            boolean stat = Affix.KIND_STAT.equals(a.kind());
            boolean behavioral = Affix.KIND_BEHAVIORAL.equals(a.kind());
            boolean hybrid = Affix.KIND_HYBRID.equals(a.kind());
            if (!stat && !behavioral && !hybrid) {
                findings.add(at + ": unknown Kind '" + a.kind() + "' (STAT | BEHAVIORAL | HYBRID)");
            }
            // A pure-STAT affix with no native effect AND no fold deltas does literally nothing.
            if (stat && isBlank(a.effectId())
                    && a.hpDelta() == 0 && a.outDamageDelta() == 0 && a.inDamageDelta() == 0 && a.lootBonus() == 0) {
                findings.add(at + ": STAT affix with no EffectId and no fold deltas is a no-op");
            }
            // A behavioral/hybrid affix without a BehaviorId never dispatches its on-hit policy.
            if ((behavioral || hybrid) && isBlank(a.behaviorId())) {
                findings.add(at + ": " + a.kind() + " affix needs a BehaviorId to dispatch");
            }
        }
        return findings;
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
