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
 * applied via {@code addInfiniteEffect} at spawn - zero Java. {@link #hpDelta} folds into the mob's frozen
 * HP mult on the maximized pre-add path (Stalwart), and {@link #outDamageDelta}/{@link #inDamageDelta} fold
 * into the frozen pipeline mults (for future affixes with no native damage stat); MVP stat affixes leave
 * these at 0. A {@link #kind} of {@code BEHAVIORAL}/{@code HYBRID} dispatches to a mod-side
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
        double outDamageDelta,
        double inDamageDelta,
        double hpDelta,
        double lootBonus,
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
    }

    /**
     * Convenience constructor without a display icon ({@code iconItemId}/{@code iconTexturePath} both
     * {@code null} = no chip icon, label only). Keeps every pre-icon call site (tests + the roll paths)
     * compiling unchanged; the codec {@link com.ziggfreed.mmomobscaling.asset.AffixAsset#toAffix()} uses
     * the full constructor with the authored {@link com.ziggfreed.mmomobscaling.asset.IconSpec}.
     */
    public Affix(@Nonnull String id, @Nonnull String displayNameKey, @Nonnull String descriptionKey,
            @Nullable String effectId, double spawnWeight, double minDifficulty,
            @Nonnull List<String> allowedRarities, double outDamageDelta, double inDamageDelta,
            double hpDelta, double lootBonus, @Nonnull String kind, @Nullable String behaviorId,
            boolean resistanceBearing) {
        this(id, displayNameKey, descriptionKey, effectId, spawnWeight, minDifficulty, allowedRarities,
                outDamageDelta, inDamageDelta, hpDelta, lootBonus, kind, behaviorId, resistanceBearing,
                null, null);
    }

    /** True when this affix authors a chip icon (an item id or a texture path) for the inspector HUD. */
    public boolean hasIcon() {
        return (iconItemId != null && !iconItemId.isBlank())
                || (iconTexturePath != null && !iconTexturePath.isBlank());
    }

    /** True when this affix may roll on the given rarity id. A wildcard {@code "*"} allows all; {@code []} allows none. */
    public boolean allowsRarity(@Nonnull String rarityId) {
        String want = rarityId.toLowerCase(Locale.ROOT);
        for (String r : allowedRarities) {
            if ("*".equals(r) || r.toLowerCase(Locale.ROOT).equals(want)) {
                return true;
            }
        }
        return false;
    }
}
