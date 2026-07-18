package com.ziggfreed.mmomobscaling.variant;

import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.instance.reward.LootEntry;
import com.ziggfreed.mmomobscaling.family.FamilyFilter;

/**
 * A resolved mob VARIANT: a family-flavored OVERLAY that stacks on top of a base rarity (the runtime model
 * decoded from a {@code Server/MmoMobScaling/Variants/*.json} {@link com.ziggfreed.mmomobscaling.asset.VariantAsset}).
 * Immutable, pure data, no engine coupling.
 *
 * <p>A variant is a SECOND, independent roll axis beside the {@link com.ziggfreed.mmomobscaling.rarity.Rarity}
 * ladder: a mob rolls its base rarity as before, then INDEPENDENTLY rolls at most one variant (family-gated).
 * The two compose - "Horrific Epic Spider" is an epic base + a horrific overlay. A variant's stat multipliers
 * stack MULTIPLICATIVELY on top of the base rarity+affix layer ({@link com.ziggfreed.mmomobscaling.scaling.MobScaleFold}),
 * and a variant contributes its OWN affix slots + allow-list (so an affix can be variant-exclusive via the
 * affix's {@code AllowedVariants} gate - the transitive "spider-only venom" pattern). A variant carries NO
 * aura/body-tint (the RARITY owns the single tint channel); its identity is the name decoration + its affix(es).
 *
 * <p>{@link #chance} is this variant's ABSOLUTE spawn probability on an eligible mob (not a relative weight):
 * {@link VariantRoster} draws once and the chances of all eligible variants partition the roll, with the
 * remainder = no variant. Author the chances of co-occurring variants to sum to at most 1.0.
 *
 * <p>{@link #bonusRewards} is the P4 ADDITIVE reward layer (mirrors {@code Rarity.bonusRewards}): authored
 * ziggfreed-common {@code LootEntry} compact specs stacked on top of the base rarity's own additive layer,
 * granted to the KILLER alongside both hosts' {@code BonusDropList} item loot. Empty (never {@code null})
 * means no additive layer for this variant.
 */
public record Variant(
        @Nonnull String id,
        @Nonnull String displayNameKey,
        double chance,
        double minDifficulty,
        double hpMult,
        double outDamageMult,
        double inDamageMult,
        double lootMult,
        double xpMult,
        int affixSlots,
        @Nonnull List<String> allowedAffixes,
        @Nonnull List<String> allowedRarities,
        @Nullable String auraEffectId,
        @Nullable String bonusDropListId,
        @Nonnull String nameColor,
        @Nonnull FamilyFilter familyFilter,
        @Nonnull List<LootEntry> bonusRewards) {

    /** The fallback display colour when a variant authors no {@code NameColor} (plain white). */
    public static final String DEFAULT_NAME_COLOR = "#ffffff";

    public Variant {
        allowedAffixes = List.copyOf(allowedAffixes);
        allowedRarities = List.copyOf(allowedRarities);
        bonusRewards = List.copyOf(bonusRewards);
    }

    /**
     * Convenience constructor without a requires-rarity gate / aura / drop list / display colour / family
     * filter / bonus-reward layer (defaults: any base rarity, no aura, no bonus drops, white,
     * {@link FamilyFilter#ALLOW_ALL}, empty {@link #bonusRewards}).
     */
    public Variant(@Nonnull String id, @Nonnull String displayNameKey, double chance, double minDifficulty,
            double hpMult, double outDamageMult, double inDamageMult, double lootMult, double xpMult,
            int affixSlots, @Nonnull List<String> allowedAffixes) {
        this(id, displayNameKey, chance, minDifficulty, hpMult, outDamageMult, inDamageMult, lootMult,
                xpMult, affixSlots, allowedAffixes, List.of("*"), null, null, "", FamilyFilter.ALLOW_ALL,
                List.of());
    }

    /**
     * Convenience constructor with a display colour + family filter but no requires-rarity gate / aura /
     * drop list / bonus-reward layer (any base rarity, no aura, no bonus drops, empty {@link #bonusRewards}).
     */
    public Variant(@Nonnull String id, @Nonnull String displayNameKey, double chance, double minDifficulty,
            double hpMult, double outDamageMult, double inDamageMult, double lootMult, double xpMult,
            int affixSlots, @Nonnull List<String> allowedAffixes, @Nonnull String nameColor,
            @Nonnull FamilyFilter familyFilter) {
        this(id, displayNameKey, chance, minDifficulty, hpMult, outDamageMult, inDamageMult, lootMult,
                xpMult, affixSlots, allowedAffixes, List.of("*"), null, null, nameColor, familyFilter,
                List.of());
    }

    /**
     * Convenience constructor with the full requires-rarity gate / aura / drop list / display colour /
     * family filter but no bonus-reward layer (the shape before P4; {@link #bonusRewards} = empty).
     */
    public Variant(@Nonnull String id, @Nonnull String displayNameKey, double chance, double minDifficulty,
            double hpMult, double outDamageMult, double inDamageMult, double lootMult, double xpMult,
            int affixSlots, @Nonnull List<String> allowedAffixes, @Nonnull List<String> allowedRarities,
            @Nullable String auraEffectId, @Nullable String bonusDropListId, @Nonnull String nameColor,
            @Nonnull FamilyFilter familyFilter) {
        this(id, displayNameKey, chance, minDifficulty, hpMult, outDamageMult, inDamageMult, lootMult,
                xpMult, affixSlots, allowedAffixes, allowedRarities, auraEffectId, bonusDropListId, nameColor,
                familyFilter, List.of());
    }

    /** The authored HUD/name display colour ({@code #rrggbb}); {@link #DEFAULT_NAME_COLOR} when unset. */
    @Nonnull
    public String displayColor() {
        return nameColor.isBlank() ? DEFAULT_NAME_COLOR : nameColor;
    }

    /** True when this variant may grant the given affix id. A wildcard {@code "*"} allows all; {@code []} allows none. */
    public boolean allowsAffix(@Nonnull String affixId) {
        return matches(allowedAffixes, affixId);
    }

    /**
     * True when this variant may overlay the given BASE rarity id. A wildcard {@code "*"} allows any base
     * (including a PLAIN mob, whose base id is {@code ""}); a specific list requires the base rarity id to be
     * present (so a plain mob only matches {@code "*"}). Mirrors {@code Rarity.allowsAffix}.
     */
    public boolean allowsRarity(@Nonnull String baseRarityId) {
        return matches(allowedRarities, baseRarityId);
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
