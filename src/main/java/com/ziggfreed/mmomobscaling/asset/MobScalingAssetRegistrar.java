package com.ziggfreed.mmomobscaling.asset;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;

import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.ziggfreed.common.asset.AssetStoreRegistrar;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.config.AffixConfig;
import com.ziggfreed.mmomobscaling.config.DifficultyConfig;
import com.ziggfreed.mmomobscaling.config.MobScalingConfig;
import com.ziggfreed.mmomobscaling.config.RarityConfig;
import com.ziggfreed.mmomobscaling.config.ScalingContentValidator;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.roster.Rosters;
import com.ziggfreed.mmomobscaling.world.DifficultyMapping;

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
    /** Keyed rarity-tier store ({@code Server/MmoMobScaling/Rarities/*.json}). */
    private static final String RARITIES_PATH = "MmoMobScaling/Rarities";
    /** Keyed affix store ({@code Server/MmoMobScaling/Affixes/*.json}). */
    private static final String AFFIXES_PATH = "MmoMobScaling/Affixes";
    /** Keyed difficulty-floor mapping store ({@code Server/MmoMobScaling/Difficulty/*.json}). */
    private static final String DIFFICULTY_PATH = "MmoMobScaling/Difficulty";

    private MobScalingAssetRegistrar() {
    }

    public static void registerAll(@Nonnull JavaPlugin plugin) {
        // Settings (single "Default"-keyed asset; a dual sync/async read - see MobScalingConfig).
        AssetStoreRegistrar.registerStore(
                MobScalingSettingsAsset.class,
                new DefaultAssetMap<String, MobScalingSettingsAsset>(),
                SETTINGS_PATH,
                MobScalingSettingsAsset::getId,
                MobScalingSettingsAsset.CODEC,
                null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, MobScalingSettingsAsset.class,
                MobScalingAssetRegistrar::onSettingsLoaded);

        // Rarities (keyed multi-asset store; folded into RarityConfig on load, read at spawn).
        AssetStoreRegistrar.registerStore(
                RarityAsset.class,
                new DefaultAssetMap<String, RarityAsset>(),
                RARITIES_PATH,
                RarityAsset::getId,
                RarityAsset.CODEC,
                null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, RarityAsset.class,
                MobScalingAssetRegistrar::onRaritiesLoaded);

        // Affixes (keyed multi-asset store; folded into AffixConfig on load, read at spawn).
        AssetStoreRegistrar.registerStore(
                AffixAsset.class,
                new DefaultAssetMap<String, AffixAsset>(),
                AFFIXES_PATH,
                AffixAsset::getId,
                AffixAsset.CODEC,
                null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, AffixAsset.class,
                MobScalingAssetRegistrar::onAffixesLoaded);

        // Difficulty-floor mappings (keyed multi-asset store; folded into DifficultyConfig on load,
        // read per spawn/presence/HUD resolve through ZoneDifficultyResolver).
        AssetStoreRegistrar.registerStore(
                DifficultyMappingAsset.class,
                new DefaultAssetMap<String, DifficultyMappingAsset>(),
                DIFFICULTY_PATH,
                DifficultyMappingAsset::getId,
                DifficultyMappingAsset.CODEC,
                null);
        plugin.getEventRegistry().register(LoadedAssetsEvent.class, DifficultyMappingAsset.class,
                MobScalingAssetRegistrar::onDifficultyLoaded);
    }

    /**
     * Fold the loaded settings store (the engine has already merged jar Default.json + Casual/Hardcore/
     * Playtest + any external pack overlay by id, last-pack-wins) into {@link MobScalingConfig}. Passes
     * the WHOLE preset map (keyed by name): the config selects the ACTIVE preset to fold as the store
     * layer over the owner file, lists the presets for {@code /mobscaling preset}, and re-folds live on a
     * swap. Does NOT skip the engine-base pack: the bundled assets ARE our defaults (mirrors Kweebec's
     * preset fold).
     */
    static void onSettingsLoaded(
            LoadedAssetsEvent<String, MobScalingSettingsAsset, DefaultAssetMap<String, MobScalingSettingsAsset>> event) {
        Map<String, MobScalingSettingsAsset> presets = new LinkedHashMap<>();
        for (Map.Entry<String, MobScalingSettingsAsset> entry : event.getAssetMap().getAssetMap().entrySet()) {
            MobScalingSettingsAsset asset = entry.getValue();
            if (asset != null) {
                presets.put(entry.getKey(), asset);
            }
        }
        if (!presets.isEmpty()) {
            MobScalingConfig.getInstance().applyStoreLayer(presets);
            try {
                MobScalingPlugin.LOGGER.atInfo().log(
                        "Mob-scaling settings store applied: %d preset(s), active '%s' (pack layer).",
                        presets.size(), MobScalingConfig.getInstance().getActivePreset());
            } catch (Throwable ignored) {
                // log-manager-less JVMs
            }
        }
    }

    /**
     * Fold the loaded rarity assets (the engine has already merged jar + pack by id) into
     * {@link RarityConfig}'s pack layer. Unlike ziggfreed-common's framework stores this mod SHIPS jar
     * defaults, so we take ALL entries (including the engine-base) - the bundled ladder IS our default.
     */
    static void onRaritiesLoaded(
            LoadedAssetsEvent<String, RarityAsset, DefaultAssetMap<String, RarityAsset>> event) {
        Map<String, Rarity> layer = new LinkedHashMap<>();
        for (Map.Entry<String, RarityAsset> entry : event.getAssetMap().getAssetMap().entrySet()) {
            RarityAsset asset = entry.getValue();
            if (asset != null) {
                layer.put(entry.getKey(), asset.toRarity());
            }
        }
        RarityConfig.getInstance().mergePackLayer(layer);
        Rosters.rebuild();
        logApplied("rarities", layer.size());
        warnFindings(ScalingContentValidator.validateRarities(layer.values()));
    }

    /** Fold the loaded affix assets into {@link AffixConfig}'s pack layer (same all-entries fold as rarities). */
    static void onAffixesLoaded(
            LoadedAssetsEvent<String, AffixAsset, DefaultAssetMap<String, AffixAsset>> event) {
        Map<String, Affix> layer = new LinkedHashMap<>();
        for (Map.Entry<String, AffixAsset> entry : event.getAssetMap().getAssetMap().entrySet()) {
            AffixAsset asset = entry.getValue();
            if (asset != null) {
                layer.put(entry.getKey(), asset.toAffix());
            }
        }
        AffixConfig.getInstance().mergePackLayer(layer);
        Rosters.rebuild();
        logApplied("affixes", layer.size());
        warnFindings(ScalingContentValidator.validateAffixes(layer.values()));
    }

    /**
     * Fold the loaded difficulty mappings into {@link DifficultyConfig}'s pack layer (same all-entries
     * fold as rarities - the bundled zone gradient IS our default). A malformed mapping (unknown
     * TargetType / blank TargetId) decodes to {@code null} via {@code toMapping} and is skipped with a
     * warning rather than poisoning the fold.
     */
    static void onDifficultyLoaded(
            LoadedAssetsEvent<String, DifficultyMappingAsset, DefaultAssetMap<String, DifficultyMappingAsset>> event) {
        Map<String, DifficultyMapping> layer = new LinkedHashMap<>();
        for (Map.Entry<String, DifficultyMappingAsset> entry : event.getAssetMap().getAssetMap().entrySet()) {
            DifficultyMappingAsset asset = entry.getValue();
            if (asset == null) {
                continue;
            }
            DifficultyMapping mapping = asset.toMapping(entry.getKey());
            if (mapping == null) {
                warnFindings(java.util.List.of("difficulty mapping '" + entry.getKey()
                        + "' skipped: TargetType must be Zone|Biome and TargetId non-blank"));
                continue;
            }
            layer.put(entry.getKey(), mapping);
        }
        DifficultyConfig.getInstance().mergePackLayer(layer);
        logApplied("difficulty mappings", layer.size());
        warnFindings(ScalingContentValidator.validateDifficultyMappings(layer.values()));
    }

    /** Log each content-validation finding as a warning; bad content degrades, it never blocks the load. */
    private static void warnFindings(@Nonnull java.util.List<String> findings) {
        for (String finding : findings) {
            try {
                MobScalingPlugin.LOGGER.atWarning().log("Mob-scaling content: " + finding);
            } catch (Throwable ignored) {
                // log-manager-less JVMs
            }
        }
    }

    private static void logApplied(@Nonnull String what, int count) {
        try {
            MobScalingPlugin.LOGGER.atInfo().log("Mob-scaling %s loaded: %d entries (pack layer).", what, count);
        } catch (Throwable ignored) {
            // log-manager-less JVMs
        }
    }
}
