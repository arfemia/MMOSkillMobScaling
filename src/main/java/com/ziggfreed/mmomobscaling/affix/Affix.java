package com.ziggfreed.mmomobscaling.affix;

import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A resolved mob AFFIX (the runtime model decoded from a {@code Server/MmoMobScaling/Affixes/*.json}
 * {@link com.ziggfreed.mmomobscaling.asset.AffixAsset}). Immutable, pure data.
 *
 * <p><b>Native-asset-first:</b> a stat-shaped affix's magnitude lives in the native {@link #effectId}
 * {@code EntityEffect} (Armored = {@code DamageResistance}, Swift = {@code HorizontalSpeedMultiplier}),
 * applied via {@code addInfiniteEffect} at spawn - zero Java. {@link #hpDelta} scales the mob's frozen
 * HP mult (Stalwart), and {@link #outDamageDelta}/{@link #inDamageDelta} scale the frozen pipeline mults
 * (for affixes with no native damage stat); each is a fraction the fold applies as {@code 1 + sum(delta)}
 * over the difficulty curve. {@link #resistancePercent} is the DECLARED mirror of the native effect's
 * percent {@code DamageResistance}: the fold cannot read the effect asset, so an affix that resists states
 * the fraction here and the effective-HP rail counts it ({@code ScalingContentValidator} reports any drift
 * between the two). A {@link #kind} of {@code BEHAVIORAL}/{@code HYBRID} dispatches to a mod-side
 * {@link AffixBehavior} by {@link #behaviorId} for the per-hit policy the engine has no native hook for
 * (Vampiric lifesteal; the Freezing on-hit trigger).
 */
public record Affix(
        @Nonnull String id,
        @Nonnull String displayNameKey,
        @Nonnull String descriptionKey,
        @Nullable String effectId,
        double spawnWeight,
        double minDifficulty,
        @Nonnull List<String> allowedRarities,
        @Nonnull List<String> allowedVariants,
        double outDamageDelta,
        double inDamageDelta,
        double hpDelta,
        double lootBonus,
        double resistancePercent,
        @Nonnull String kind,
        @Nullable String behaviorId,
        boolean resistanceBearing,
        @Nullable String iconItemId,
        @Nullable String iconTexturePath) {

    /** Affix kinds. STAT = pure native effect; BEHAVIORAL = mod-side on-hit; HYBRID = native effect + on-hit trigger. */
    public static final String KIND_STAT = "STAT";
    public static final String KIND_BEHAVIORAL = "BEHAVIORAL";
    public static final String KIND_HYBRID = "HYBRID";

    public Affix {
        allowedRarities = List.copyOf(allowedRarities);
        allowedVariants = List.copyOf(allowedVariants);
    }

    /**
     * Convenience constructor without an {@code allowedVariants} gate (defaults to {@code []} = this affix is
     * NOT granted by any variant, only by a rarity via {@code allowedRarities}), a declared resistance mirror
     * (0) or a display icon. Keeps every pre-variant / pre-icon call site (tests + the roll paths) compiling
     * unchanged; the codec {@link com.ziggfreed.mmomobscaling.asset.AffixAsset#toAffix()} uses the full
     * constructor.
     */
    public Affix(@Nonnull String id, @Nonnull String displayNameKey, @Nonnull String descriptionKey,
            @Nullable String effectId, double spawnWeight, double minDifficulty,
            @Nonnull List<String> allowedRarities, double outDamageDelta, double inDamageDelta,
            double hpDelta, double lootBonus, @Nonnull String kind, @Nullable String behaviorId,
            boolean resistanceBearing) {
        this(id, displayNameKey, descriptionKey, effectId, spawnWeight, minDifficulty, allowedRarities,
                List.of(), outDamageDelta, inDamageDelta, hpDelta, lootBonus, 0.0, kind, behaviorId,
                resistanceBearing, null, null);
    }

    /** Convenience constructor with an icon but the pre-variant gate list order (adds {@code allowedVariants}). */
    public Affix(@Nonnull String id, @Nonnull String displayNameKey, @Nonnull String descriptionKey,
            @Nullable String effectId, double spawnWeight, double minDifficulty,
            @Nonnull List<String> allowedRarities, double outDamageDelta, double inDamageDelta,
            double hpDelta, double lootBonus, @Nonnull String kind, @Nullable String behaviorId,
            boolean resistanceBearing, @Nullable String iconItemId, @Nullable String iconTexturePath) {
        this(id, displayNameKey, descriptionKey, effectId, spawnWeight, minDifficulty, allowedRarities,
                List.of(), outDamageDelta, inDamageDelta, hpDelta, lootBonus, 0.0, kind, behaviorId,
                resistanceBearing, iconItemId, iconTexturePath);
    }

    /** True when this affix authors a chip icon (an item id or a texture path) for the inspector HUD. */
    public boolean hasIcon() {
        return (iconItemId != null && !iconItemId.isBlank())
                || (iconTexturePath != null && !iconTexturePath.isBlank());
    }

    /** True when this affix may roll on the given rarity id. A wildcard {@code "*"} allows all; {@code []} allows none. */
    public boolean allowsRarity(@Nonnull String rarityId) {
        return matches(allowedRarities, rarityId);
    }

    /**
     * True when this affix may be granted by the given VARIANT id. A wildcard {@code "*"} allows any variant;
     * an empty list (the default) allows NONE - an affix is variant-granted only when it opts in via
     * {@code AllowedVariants}, so the shipped rarity affixes never leak onto a variant's slots.
     */
    public boolean allowsVariant(@Nonnull String variantId) {
        return matches(allowedVariants, variantId);
    }

    private static boolean matches(@Nonnull List<String> ids, @Nonnull String want) {
        String w = want.toLowerCase(Locale.ROOT);
        for (String id : ids) {
            if ("*".equals(id) || id.toLowerCase(Locale.ROOT).equals(w)) {
                return true;
            }
        }
        return false;
    }
}
