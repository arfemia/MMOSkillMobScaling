package com.ziggfreed.mmomobscaling.config;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.mmomobscaling.affix.Affix;

/**
 * The {@code defaults < pack < owner} fold authority for {@link Affix}es, keyed by lowercase affix id.
 * Mirrors {@link RarityConfig}: the shared ziggfreed-common {@link AbstractKeyedAssetConfig} base owns the
 * fold; this singleton adds only the {@link Affix} binding + {@link #getInstance()}. Populated lazily by the
 * {@code LoadedAssetsEvent} fold in {@code MobScalingAssetRegistrar}; read at spawn.
 */
public final class AffixConfig extends AbstractKeyedAssetConfig<Affix> {

    private static final AffixConfig INSTANCE = new AffixConfig();

    @Nonnull
    public static AffixConfig getInstance() {
        return INSTANCE;
    }

    private AffixConfig() {
    }
}
