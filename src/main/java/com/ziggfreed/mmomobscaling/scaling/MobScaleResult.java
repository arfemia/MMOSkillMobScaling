package com.ziggfreed.mmomobscaling.scaling;

import javax.annotation.Nonnull;

/**
 * The FROZEN scaling result for one mob: the resolved difficulty + rarity/affix identity + the pre-folded
 * multipliers everything downstream reads. Computed ONCE at spawn by {@link MobScaleFold} and stamped on the
 * mob's {@code ScaledMobComponent}; there is zero per-tick recompute. The damage filter reads
 * {@link #outDmgMult}/{@link #inDmgMult}, the death path reads {@link #lootMult}/{@link #xpMult}, and the
 * effect-apply reads {@link #rarityId}/{@link #affixIds} to resolve the native aura + affix effects. The
 * kill-XP reward reads {@link #xpMult} (+ {@link #difficulty} for the underdog bonus); {@link #lootMult} is
 * decoded and folded but NOT yet consumed (native item-drop loot is a follow-up).
 *
 * <p>All affix damage is PRE-FOLDED into the scalar mults here, so the per-hit path is a single float
 * multiply with zero affix iteration. Pure data (no engine coupling); {@code affixIds} identity-compares in
 * record equality, which is fine since results are never used as map keys.
 */
public record MobScaleResult(
        float difficulty,
        @Nonnull String rarityId,
        @Nonnull String variantId,
        @Nonnull String[] affixIds,
        float hpMult,
        float outDmgMult,
        float inDmgMult,
        float lootMult,
        float xpMult,
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
