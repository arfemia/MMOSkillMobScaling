package com.ziggfreed.mmomobscaling.i18n;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.variant.Variant;

/**
 * Resolves the localization KEY for a rarity/affix display string: the asset's explicit {@code DisplayNameKey}
 * / {@code DescriptionKey} when authored, else the convention key {@code mmomobscaling.<type>.<id>.<field>} (so an
 * omitted key never renders {@code ""}, the C-loc1 fix). Pure String logic - the render site (Phase 5) wraps
 * the returned key in a Hytale {@code Message} the client resolves against the mod's own {@code mmomobscaling.lang}.
 * Keeping resolution here (not a threaded locale) keeps display text client-side.
 */
public final class MobScalingTextUtil {

    private MobScalingTextUtil() {
    }

    /** The key for a rarity's display name: explicit {@code DisplayNameKey}, else {@code mmomobscaling.rarity.<id>.name}. */
    @Nonnull
    public static String rarityNameKey(@Nonnull Rarity rarity) {
        return keyOr(rarity.displayNameKey(), "mmomobscaling.rarity." + lower(rarity.id()) + ".name");
    }

    /** The key for a variant's display name: explicit {@code DisplayNameKey}, else {@code mmomobscaling.variant.<id>.name}. */
    @Nonnull
    public static String variantNameKey(@Nonnull Variant variant) {
        return keyOr(variant.displayNameKey(), "mmomobscaling.variant." + lower(variant.id()) + ".name");
    }

    /** The key for an affix's display name: explicit {@code DisplayNameKey}, else {@code mmomobscaling.affix.<id>.name}. */
    @Nonnull
    public static String affixNameKey(@Nonnull Affix affix) {
        return keyOr(affix.displayNameKey(), "mmomobscaling.affix." + lower(affix.id()) + ".name");
    }

    /** The key for an affix's qualitative description: explicit {@code DescriptionKey}, else {@code mmomobscaling.affix.<id>.desc}. */
    @Nonnull
    public static String affixDescKey(@Nonnull Affix affix) {
        return keyOr(affix.descriptionKey(), "mmomobscaling.affix." + lower(affix.id()) + ".desc");
    }

    @Nonnull
    private static String keyOr(@Nullable String explicit, @Nonnull String convention) {
        return (explicit != null && !explicit.isBlank()) ? explicit : convention;
    }

    @Nonnull
    private static String lower(@Nonnull String id) {
        return id.toLowerCase(Locale.ROOT);
    }
}
