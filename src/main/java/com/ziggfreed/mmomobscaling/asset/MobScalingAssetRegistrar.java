package com.ziggfreed.mmomobscaling.asset;

import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;

import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.ziggfreed.common.asset.AssetStoreRegistrar;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.config.MobScalingConfig;

/**
 * Registers this mod's OWN Pattern-A asset stores + their {@code LoadedAssetsEvent} listeners
 * during plugin {@code setup()}, routing through ziggfreed-common's {@link AssetStoreRegistrar}
 * (the exemplar is Kweebec's {@code KweebecAssetRegistrar}). A store makes the settings a REAL
 * claimed Hytale asset (pack-overridable, no unclaimed-file ambiguity), not just a bundled resource.
 *
 * <p>Note the DUAL read of the settings, both driven by the SAME codec:
 * <ul>
 *   <li>SYNCHRONOUS at {@code setup()}: {@code MobScalingConfig.load()} decodes the jar Default.json
 *       + the owner file so the zero-cost registration gate can read {@code Enabled} immediately
 *       (this runs BEFORE {@code LoadedAssetsEvent}).</li>
 *   <li>ASYNC on {@code LoadedAssetsEvent}: the store's folded (jar + pack) settings asset is
 *       re-applied over the owner file via {@link MobScalingConfig#applyStoreLayer}, so a content
 *       pack can override the runtime-read settings. (The gate already fired; a change to
 *       {@code Enabled} needs a restart, as documented.)</li>
 * </ul>
 */
public final class MobScalingAssetRegistrar {

    /** Content path under a pack's {@code Server/} (i.e. {@code Server/MmoMobScaling/Settings/*.json}). */
    private static final String SETTINGS_PATH = "MmoMobScaling/Settings";

    private MobScalingAssetRegistrar() {
    }

    public static void registerAll(@Nonnull JavaPlugin plugin) {
        AssetStoreRegistrar.registerStore(
                MobScalingSettingsAsset.class,
                new DefaultAssetMap<String, MobScalingSettingsAsset>(),
                SETTINGS_PATH,
                MobScalingSettingsAsset::getId,
                MobScalingSettingsAsset.CODEC,
                null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, MobScalingSettingsAsset.class,
                MobScalingAssetRegistrar::onSettingsLoaded);
    }

    /**
     * Fold the loaded settings asset (the engine has already merged the jar Default.json with any
     * external pack overlay by id, last-pack-wins) over the owner file. Does NOT skip the engine-base
     * pack: the bundled {@code Default.json} IS our default (mirrors Kweebec's preset fold). The
     * settings are a single "Default"-keyed asset; if a pack adds more we take the "default" entry.
     */
    static void onSettingsLoaded(
            LoadedAssetsEvent<String, MobScalingSettingsAsset, DefaultAssetMap<String, MobScalingSettingsAsset>> event) {
        DefaultAssetMap<String, MobScalingSettingsAsset> assetMap = event.getAssetMap();
        MobScalingSettingsAsset merged = null;
        MobScalingSettingsAsset anyEntry = null;
        for (Map.Entry<String, MobScalingSettingsAsset> entry : assetMap.getAssetMap().entrySet()) {
            MobScalingSettingsAsset asset = entry.getValue();
            if (asset == null) {
                continue;
            }
            anyEntry = asset;
            if ("default".equals(entry.getKey().toLowerCase(Locale.ROOT))) {
                merged = asset;
            }
        }
        MobScalingSettingsAsset effective = merged != null ? merged : anyEntry;
        if (effective != null) {
            MobScalingConfig.getInstance().applyStoreLayer(effective);
            try {
                MobScalingPlugin.LOGGER.atInfo().log("Mob-scaling settings asset applied (pack layer).");
            } catch (Throwable ignored) {
                // log-manager-less JVMs
            }
        }
    }
}
