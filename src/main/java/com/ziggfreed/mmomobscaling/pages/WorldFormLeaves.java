package com.ziggfreed.mmomobscaling.pages;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.Hud;
import com.ziggfreed.mmomobscaling.asset.WorldSettings;

/**
 * The one place the per-world form's collected leaves are reconciled with what the file already authors
 * before they are written. Pure and engine-free, so it is unit-tested apart from the page.
 *
 * <p>The world form collects with {@code blankIsInherit}: a blank TEXT field becomes a {@code null}
 * leaf, which removes the leaf from the file so it inherits again. For the two name-key prefixes an
 * EMPTY string is itself a value ("no prefix: prettify the raw id"), and it seeds the form as the same
 * blank field an unauthored prefix does. Without this step a Save of a world that deliberately authors
 * an empty prefix would remove it and quietly switch that world back to the inherited prefix.
 * {@link #keepAuthoredEmptyText} puts an authored empty string back where the form left a blank, so an
 * authored empty prefix survives a round trip; a blank over an authored NON-empty prefix still means
 * inherit, exactly as before, and a blank over nothing authored stays inherit too.
 */
final class WorldFormLeaves {

    /** The world-form TEXT leaves whose empty string is a value rather than an absence. */
    static final String ZONE_PREFIX_LEAF = "ZoneHud.ZoneNameKeyPrefix";
    static final String BIOME_PREFIX_LEAF = "ZoneHud.BiomeNameKeyPrefix";
    static final List<String> EMPTY_IS_A_VALUE = List.of(ZONE_PREFIX_LEAF, BIOME_PREFIX_LEAF);

    private WorldFormLeaves() {
    }

    /**
     * For each leaf in {@link #EMPTY_IS_A_VALUE} that the form collected as blank ({@code null}) while
     * {@code authored} (the file's own pre-save body, {@code null} for a brand-new file) carries an
     * EMPTY string there, put the empty string back so the save keeps it. Every other leaf is untouched.
     */
    static void keepAuthoredEmptyText(@Nonnull Map<String, Object> leaves, @Nullable WorldSettings authored) {
        Hud hud = authored == null ? null : authored.getZoneHud();
        if (hud == null) {
            return;
        }
        keepEmpty(leaves, ZONE_PREFIX_LEAF, hud.getZoneNameKeyPrefix());
        keepEmpty(leaves, BIOME_PREFIX_LEAF, hud.getBiomeNameKeyPrefix());
    }

    private static void keepEmpty(@Nonnull Map<String, Object> leaves, @Nonnull String path,
            @Nullable String authoredValue) {
        if (authoredValue != null && authoredValue.isEmpty() && leaves.containsKey(path) && leaves.get(path) == null) {
            leaves.put(path, "");
        }
    }
}
