package com.ziggfreed.mmomobscaling.scaling;

import javax.annotation.Nonnull;

/**
 * The FROZEN scaling result for one mob: the two resolved difficulties + rarity/affix identity + the pre-folded
 * multipliers everything downstream reads. Computed ONCE at spawn by {@link MobScaleFold} and stamped on the
 * mob's {@code ScaledMobComponent}; there is zero per-tick recompute. The damage filter reads
 * {@link #inDmgMult}, the effect-apply carries {@link #outDmgMult} as a native outgoing-damage effect and
 * reads {@link #rarityId}/{@link #affixIds} to resolve the native aura + affix effects, the death path reads
 * {@link #lootMult}, and the caster arm gates on {@link #rollDifficulty}.
 *
 * <p>The two difficulties are {@link SpawnDifficulty}'s split: {@link #difficulty} is what the mob's health and
 * damage read ({@link SpawnDifficulty#stat()}, the place itself at the shipped {@code GroupDeltaStatShare} of 0),
 * the number the HUD's danger word, the inspector and the {@code mob_difficulty} factor compare against;
 * {@link #rollDifficulty} is what its rarity, variant, affixes and caster abilities rolled at
 * ({@link SpawnDifficulty#roll()}, the place plus the nearby group's power). A mob nothing rolled for (a plain
 * fold, an encounter's companion-scaled boss) carries the same number in both.
 *
 * <p>No kill-reward multiplier lives here: kill XP follows a mob's maximum health (R610), which already carries
 * its rarity and its party.
 *
 * <p>All affix damage is PRE-FOLDED into the scalar mults here, so neither damage direction walks affixes
 * per hit. Pure data (no engine coupling); {@code affixIds} identity-compares in record equality, which is
 * fine since results are never used as map keys.
 */
public record MobScaleResult(
        float difficulty,
        float rollDifficulty,
        @Nonnull String rarityId,
        @Nonnull String variantId,
        @Nonnull String[] affixIds,
        float hpMult,
        float outDmgMult,
        float inDmgMult,
        float lootMult,
        byte scope) {

    /** Classification scope: a normal hostile mob (rolls rarity/affixes off the curve). */
    public static final byte SCOPE_HOSTILE = 0;
    /** Classification scope: an authored boss (forced rarity, own curve). */
    public static final byte SCOPE_BOSS = 1;

    /** True when a non-plain rarity was rolled/forced (an empty id means plain). */
    public boolean hasRarity() {
        return !rarityId.isEmpty();
    }

    /** True when a variant overlay was rolled (an empty id means no variant). */
    public boolean hasVariant() {
        return !variantId.isEmpty();
    }

    /** True when at least one affix is present. */
    public boolean hasAffixes() {
        return affixIds.length > 0;
    }
}
