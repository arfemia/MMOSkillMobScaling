package com.ziggfreed.mmomobscaling.config;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.mmomobscaling.rarity.Rarity;

/**
 * The {@code defaults < pack < owner} fold authority for {@link Rarity} tiers, keyed by lowercase rarity id.
 * The fold mechanics (three layers, lower-casing, idempotent re-import, resolve order) live in the shared
 * ziggfreed-common {@link AbstractKeyedAssetConfig} base; this singleton adds only the {@link Rarity} type
 * binding + {@link #getInstance()}. Populated LAZILY by the {@code LoadedAssetsEvent} fold in
 * {@code MobScalingAssetRegistrar} AFTER plugin {@code setup()}; read at spawn time (well after load).
 *
 * <p>Unlike ziggfreed-common's framework stores, this mod SHIPS jar defaults (the starter ladder under
 * {@code Server/MmoMobScaling/Rarities/}); the engine merges them with any pack overlay by id before the
 * fold, so the pack layer here already carries jar + pack.
 */
public final class RarityConfig extends AbstractKeyedAssetConfig<Rarity> {

    private static final RarityConfig INSTANCE = new RarityConfig();

    @Nonnull
    public static RarityConfig getInstance() {
        return INSTANCE;
    }

    private RarityConfig() {
    }
}
