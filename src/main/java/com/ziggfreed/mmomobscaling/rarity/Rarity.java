package com.ziggfreed.mmomobscaling.rarity;

import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.loot.LootRef;
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
 *
 * <p>{@link #loot} is everything this tier hands over when a mob wearing it dies: the shared
 * ziggfreed-common {@code LootRef} vocabulary (named {@code Lootables} and/or inline {@code Rolls} whose
 * {@code Grants} carry items, native {@code DropLists}, commands and registered reward kinds), rolled by
 * {@code MobScalingLootDropSystem}. {@code null} means this tier pays nothing extra. It is the same
 * vocabulary a station's rare find and a quest reward speak, so a table authored for one works here.
 *
 * <p>{@link #familyFilter} is the tier's family TARGETING block ({@code AllowGroups}/{@code DenyGroups}/
 * {@code AllowRoles}/{@code DenyRoles}/{@code ForceGroups}/{@code ForceRoles} on the asset): which families
 * may roll it, which never may, and which ALWAYS get at least it. It holds only pure data - the
 * engine-coupled evaluation against a spawning mob is {@code family/MobFamilyMatcher}. Absent =
 * {@link FamilyFilter#ALLOW_ALL} (every mob eligible, nothing forced).
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
        @Nonnull List<String> allowedAffixes,
        @Nonnull String nameColor,
        @Nonnull FamilyFilter familyFilter,
        @Nullable LootRef loot) {

    /** The fallback display colour when a tier authors no {@code NameColor} (plain white). */
    public static final String DEFAULT_NAME_COLOR = "#ffffff";

    public Rarity {
        allowedAffixes = List.copyOf(allowedAffixes);
    }

    /**
     * Convenience constructor without a display colour, family filter, or death loot
     * ({@code NameColor} absent = empty = white; family filter = {@link FamilyFilter#ALLOW_ALL} = every mob
     * eligible; {@link #loot} = none).
     */
    public Rarity(@Nonnull String id, @Nonnull String displayNameKey, double weight, double minDifficulty,
            double hpMult, double outDamageMult, double inDamageMult, double lootMult, double xpMult,
            int affixSlots, @Nullable String auraEffectId, @Nonnull List<String> allowedAffixes) {
        this(id, displayNameKey, weight, minDifficulty, hpMult, outDamageMult, inDamageMult, lootMult,
                xpMult, affixSlots, auraEffectId, allowedAffixes, "", FamilyFilter.ALLOW_ALL, null);
    }

    /**
     * Convenience constructor with a display colour but no family filter or death loot
     * ({@link FamilyFilter#ALLOW_ALL}; {@link #loot} = none).
     */
    public Rarity(@Nonnull String id, @Nonnull String displayNameKey, double weight, double minDifficulty,
            double hpMult, double outDamageMult, double inDamageMult, double lootMult, double xpMult,
            int affixSlots, @Nullable String auraEffectId, @Nonnull List<String> allowedAffixes,
            @Nonnull String nameColor) {
        this(id, displayNameKey, weight, minDifficulty, hpMult, outDamageMult, inDamageMult, lootMult,
                xpMult, affixSlots, auraEffectId, allowedAffixes, nameColor, FamilyFilter.ALLOW_ALL, null);
    }

    /** Convenience constructor with a display colour + family filter but no death loot. */
    public Rarity(@Nonnull String id, @Nonnull String displayNameKey, double weight, double minDifficulty,
            double hpMult, double outDamageMult, double inDamageMult, double lootMult, double xpMult,
            int affixSlots, @Nullable String auraEffectId, @Nonnull List<String> allowedAffixes,
            @Nonnull String nameColor, @Nonnull FamilyFilter familyFilter) {
        this(id, displayNameKey, weight, minDifficulty, hpMult, outDamageMult, inDamageMult, lootMult,
                xpMult, affixSlots, auraEffectId, allowedAffixes, nameColor, familyFilter, null);
    }

    /**
     * Ordering scalar for "which tier is stronger", used wherever two tiers must be compared outside the
     * weighted roll (the {@code ForceGroups}/{@code ForceRoles} resolution: highest forced tier wins, and a
     * normal roll only replaces a forced tier when it is stronger still). Derived from the authored combat
     * multipliers rather than {@code minDifficulty}, because an off-ladder tier (weight 0, e.g. a boss tier)
     * carries no meaningful band. Comparison order is HP, then outgoing damage, then id - a total,
     * content-determined order, so it never depends on map iteration.
     */
    public int compareStrength(@Nonnull Rarity other) {
        int cmp = Double.compare(this.hpMult, other.hpMult);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Double.compare(this.outDamageMult, other.outDamageMult);
        return cmp != 0 ? cmp : this.id.compareTo(other.id);
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
