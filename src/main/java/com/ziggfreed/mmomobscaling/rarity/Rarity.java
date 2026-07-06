package com.ziggfreed.mmomobscaling.rarity;

import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.mmomobscaling.family.FamilyFilter;

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
 *
 * <p>{@link #familyFilter} gates WHICH mob families this tier may roll on (an {@code AllowGroups}/
 * {@code DenyGroups}/{@code AllowRoles}/{@code DenyRoles} {@code Families} block on the asset); it holds only
 * pure data - the engine-coupled evaluation against a spawning mob is {@code family/MobFamilyMatcher}. Absent
 * = {@link FamilyFilter#ALLOW_ALL} (every mob eligible, the pre-feature behavior).
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
        @Nonnull List<String> allowedAffixes,
        @Nonnull String nameColor,
        @Nonnull FamilyFilter familyFilter) {

    /** The fallback display colour when a tier authors no {@code NameColor} (plain white). */
    public static final String DEFAULT_NAME_COLOR = "#ffffff";

    public Rarity {
        allowedAffixes = List.copyOf(allowedAffixes);
    }

    /**
     * Convenience constructor without a display colour or family filter ({@code NameColor} absent = empty =
     * white; family filter = {@link FamilyFilter#ALLOW_ALL} = every mob eligible).
     */
    public Rarity(@Nonnull String id, @Nonnull String displayNameKey, double weight, double minDifficulty,
            double hpMult, double outDamageMult, double inDamageMult, double lootMult, double xpMult,
            int affixSlots, @Nullable String auraEffectId, @Nullable String bonusDropListId,
            @Nonnull List<String> allowedAffixes) {
        this(id, displayNameKey, weight, minDifficulty, hpMult, outDamageMult, inDamageMult, lootMult,
                xpMult, affixSlots, auraEffectId, bonusDropListId, allowedAffixes, "", FamilyFilter.ALLOW_ALL);
    }

    /** Convenience constructor with a display colour but no family filter ({@link FamilyFilter#ALLOW_ALL}). */
    public Rarity(@Nonnull String id, @Nonnull String displayNameKey, double weight, double minDifficulty,
            double hpMult, double outDamageMult, double inDamageMult, double lootMult, double xpMult,
            int affixSlots, @Nullable String auraEffectId, @Nullable String bonusDropListId,
            @Nonnull List<String> allowedAffixes, @Nonnull String nameColor) {
        this(id, displayNameKey, weight, minDifficulty, hpMult, outDamageMult, inDamageMult, lootMult,
                xpMult, affixSlots, auraEffectId, bonusDropListId, allowedAffixes, nameColor,
                FamilyFilter.ALLOW_ALL);
    }

    /** The authored HUD/name display colour ({@code #rrggbb}); {@link #DEFAULT_NAME_COLOR} when unset. */
    @Nonnull
    public String displayColor() {
        return nameColor.isBlank() ? DEFAULT_NAME_COLOR : nameColor;
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
