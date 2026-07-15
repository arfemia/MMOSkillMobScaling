package com.ziggfreed.mmomobscaling.config;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.mmomobscaling.caster.CasterRoster;

/**
 * The {@code defaults < pack < owner} fold authority for {@link CasterRoster}s, keyed by lowercase
 * roster id (the asset filename). The fold mechanics (three layers, lower-casing, idempotent
 * re-import, resolve order) live in the shared ziggfreed-common {@link AbstractKeyedAssetConfig} base;
 * this singleton adds only the {@link CasterRoster} type binding + {@link #getInstance()}. Populated
 * LAZILY by the {@code LoadedAssetsEvent} fold in {@code MobScalingAssetRegistrar} AFTER plugin
 * {@code setup()}; read (via {@code roster/Rosters.casterRosters()}) at spawn time.
 */
public final class CasterRosterConfig extends AbstractKeyedAssetConfig<CasterRoster> {

    private static final CasterRosterConfig INSTANCE = new CasterRosterConfig();

    @Nonnull
    public static CasterRosterConfig getInstance() {
        return INSTANCE;
    }

    private CasterRosterConfig() {
    }
}
