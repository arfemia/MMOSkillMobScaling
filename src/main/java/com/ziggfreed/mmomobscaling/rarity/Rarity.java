package com.ziggfreed.mmomobscaling.rarity;

import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A resolved mob RARITY tier (the runtime model decoded from a {@code Server/MmoMobScaling/Rarities/*.json}
 * {@link com.ziggfreed.mmomobscaling.asset.RarityAsset}). Immutable, pure data - no engine coupling - so it
 * is unit-testable and safe to read off the frozen spawn path.
 *
 * <p>The stat multipliers are folded into the frozen {@code ScaledMobComponent} at spawn (Phase 5): HP via
 * the pre-add {@code HealthUtil.scaleMaxHealth} (maximized), out/in damage into the pipeline mults, loot/xp
 * into the reward path. {@link #auraEffectId} is a native {@code EntityEffect} (e.g. {@code Mmoscaling_Aura_Epic})
 * applied via {@code addInfiniteEffect} - the native-asset-first visual channel, zero Java.
 * {@link #bonusDropListId} is a native {@code ItemDropList} ({@code Server/Drops/*}) pulled on death by
 * {@code MobScalingLootDropSystem}; {@code null} means no bonus loot for this tier.
 */
public record Rarity(
        @Nonnull String id,
        @Nonnull String displayNameKey,
        double weight,
        double minDifficulty,
        double hpMult,
        double outDamageMult,
        double inDamageMult,
        double lootMult,
        double xpMult,
        int affixSlots,
        @Nullable String auraEffectId,
        @Nullable String bonusDropListId,
        @Nonnull List<String> allowedAffixes) {

    public Rarity {
        allowedAffixes = List.copyOf(allowedAffixes);
    }

    /** True when this rarity may roll the given affix id. A wildcard {@code "*"} allows all; {@code []} allows none. */
    public boolean allowsAffix(@Nonnull String affixId) {
        String want = affixId.toLowerCase(Locale.ROOT);
        for (String a : allowedAffixes) {
            if ("*".equals(a) || a.toLowerCase(Locale.ROOT).equals(want)) {
                return true;
            }
        }
        return false;
    }
}
