package com.ziggfreed.mmomobscaling.config;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.mmomobscaling.variant.Variant;

/**
 * The {@code defaults < pack < owner} fold authority for {@link Variant} overlays, keyed by lowercase variant
 * id. Mirrors {@link RarityConfig}/{@link AffixConfig}: the shared ziggfreed-common
 * {@link AbstractKeyedAssetConfig} base owns the fold; this singleton adds only the {@link Variant} binding +
 * {@link #getInstance()}. Populated lazily by the {@code LoadedAssetsEvent} fold in
 * {@code MobScalingAssetRegistrar}; read at spawn.
 */
public final class VariantConfig extends AbstractKeyedAssetConfig<Variant> {

    private static final VariantConfig INSTANCE = new VariantConfig();

    @Nonnull
    public static VariantConfig getInstance() {
        return INSTANCE;
    }

    private VariantConfig() {
    }
}
