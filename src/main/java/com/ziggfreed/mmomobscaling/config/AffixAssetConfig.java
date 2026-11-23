package com.ziggfreed.mmomobscaling.config;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.mmomobscaling.asset.AffixAsset;

/**
 * The {@code defaults < pack < owner} fold of the affix ASSETS, keyed by lowercase affix id: the shipped and pack
 * {@code Server/MmoMobScaling/Affixes/*.json} files as the pack layer, the server owner's
 * {@code mods/MmoMobScaling/affixes.json} entries as the owner layer, each decoded leaf by leaf against the pack's
 * affix ({@link AffixOwnerLayer}, through CommonLib's one owner reader). It is what the owner form edits; the runtime
 * reads {@link AffixConfig}, the {@code Affix} records {@link AffixOwnerLayer#refold()} derives from this fold.
 */
public final class AffixAssetConfig extends AbstractKeyedAssetConfig<AffixAsset> {

    private static final AffixAssetConfig INSTANCE = new AffixAssetConfig();

    @Nonnull
    public static AffixAssetConfig getInstance() {
        return INSTANCE;
    }

    private AffixAssetConfig() {
    }
}
