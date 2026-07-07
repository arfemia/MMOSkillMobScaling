package com.ziggfreed.mmomobscaling.config;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.logger.HytaleLogger;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.Difficulty;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.DistanceEscalation;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.Hud;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.InspectorHud;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.OpenWorld;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.StatCurve;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.WorldOverride;
import com.ziggfreed.mmomobscaling.scaling.MobScaleFold;
import com.ziggfreed.mmomobscaling.world.WorldOverrideMatcher;

/**
 * The open-world mob-scaling configuration, driven ENTIRELY by an asset codec
 * ({@link MobScalingSettingsAsset}, Pattern A, PascalCase, NESTED sub-objects) - never Java-baked
 * values, never a loose JSON blob. The authoritative defaults ship as a codec asset
 * ({@code Server/MmoMobScaling/Settings/Default.json}); owners override any key in
 * {@code mods/MmoMobScaling/mob-scaling.json} (the SAME PascalCase codec shape, partial allowed,
 * including a partially-filled nested group).
 *
 * <p>Both layers are decoded SYNCHRONOUSLY via {@code MobScalingSettingsAsset.CODEC.decodeJson(...)}
 * at plugin {@code setup()} (the {@code WorldRulesConfig.decodeOwnerRule} pattern), so the zero-cost
 * registration gate can read {@link #isEnabled()} before a {@code LoadedAssetsEvent} async asset
 * store would populate. The effective value of each LEAF is owner-over-store-over-jar, computed here
 * ({@link #fold3} walks a nested group per layer; an absent group or leaf falls to the next layer).
 *
 * <p><b>Convention (do NOT regress):</b> config data is defined by an asset codec, PascalCase, under
 * {@code Server/}; there are no Java default VALUES in this class (only a neutral fail-safe used when
 * a broken jar is missing its bundled default asset).
 */
public final class MobScalingConfig implements SpawnScalingSettings {

    /** Jar-bundled authoritative defaults, decoded via the codec (classpath resource). */
    private static final String DEFAULTS_RESOURCE = "/Server/MmoMobScaling/Settings/Default.json";

    /** The reference dump filename written under {@code mods/MmoMobScaling/_reference/}. */
    private static final String REFERENCE_FILE = "defaults-mob-scaling.json";

    /**
     * The empty override scaffold seeded at {@code mods/MmoMobScaling/mob-scaling.json} on first run.
     * It carries NO real overrides (an empty {@code {}} folds to the jar defaults on every leaf), only a
     * self-documenting {@code $Comment} the codec ignores, so a fresh install has a file to edit + a
     * pointer to the full schema.
     */
    private static final String OWNER_SCAFFOLD =
            "{\n"
          + "  \"$Comment\": \"MMO Mob Scaling owner overrides. Starts EMPTY: every setting falls back to the "
          + "jar default. Copy any key you want to change from _reference/" + REFERENCE_FILE + " into this "
          + "object (same PascalCase shape, partial allowed; a nested group may be partially filled). Changes "
          + "apply on server restart.\"\n"
          + "}\n";

    /**
     * This class's OWN logger (NOT {@code MobScalingPlugin.LOGGER}: that class is unloadable in a plain unit
     * JVM via the JavaPlugin -> PluginBase -> MetricsRegistry static-init chain, and this config is unit-tested).
     * Initialized in a guard so a log-manager-less JVM never poisons the class; {@link #warn} null-checks it.
     */
    @Nullable private static final HytaleLogger LOGGER = initLogger();

    @Nullable
    private static HytaleLogger initLogger() {
        try {
            return HytaleLogger.forEnclosingClass();
        } catch (Throwable t) {
            return null;
        }
    }

    private static MobScalingConfig instance;

    @Nullable private Path configPath;

    /**
     * The jar-bundled default decode, CACHED at {@link #load()}. The lowest fold layer, so a PARTIAL pack
     * override at {@link #applyStoreLayer} (a Pattern-A asset is a wholesale replace by id, not a field merge)
     * cannot drop a key and silently fold {@code enabled} to the fail-safe {@code false} - the jar value
     * survives underneath the store + owner layers.
     */
    @Nullable private MobScalingSettingsAsset jarDefaults;

    /**
     * The loaded settings STORE (keyed by preset name: "Default", "Casual", ...), captured at
     * {@link #applyStoreLayer}. The active preset ({@link #activePreset}) selects one entry to fold as the
     * store layer; {@link #availablePresetNames()} lists the keys and {@link #swapActivePreset} re-folds
     * live from it. {@code null} until the first {@code LoadedAssetsEvent} (the synchronous load() has
     * only the jar Default + owner, no store).
     */
    @Nullable private volatile Map<String, MobScalingSettingsAsset> storePresets;

    // Which preset folds as the store layer. Resolved owner-over-jar at load() (a preset asset never
    // picks the ACTIVE preset - that would be circular), then mutable via swapActivePreset. Defaults "Default".
    @Nonnull private volatile String activePreset = "Default";

    // Effective (owner > store > jar) settings. The spawn-path reads are volatile so a value written on
    // the asset-load thread is visible on the world threads that read it per spawn.
    private volatile boolean enabled;
    private boolean compositionEnabled;
    // Group-power delta may only raise a region's difficulty over the floor, never soften it. Spawn-path read.
    private volatile boolean onlyRaiseDifficulty = true;
    // Whether player/group-based scaling applies at all (1.0.1; default on). Spawn-path read (volatile);
    // a world with PlayerScalingEnabled=false pins difficulty to the escalated floor.
    private volatile boolean playerScalingEnabled = true;
    @Nonnull private String presetMode = "";
    // Intensity multiplier on the difficulty->stat curve slopes (1.0.1; default 1.0, clamped >= 0). Spawn-path
    // read (statCurveModel) so volatile; runtime-tunable via setIntensityRuntime (/mobscaling intensity).
    private volatile double intensity = 1.0;
    private volatile double raritySpawnChance;
    private boolean allowDifficultyIncreaseOnPartyJoin;
    private double lateArrivalBumpFactor;
    @Nonnull private String openWorldAggregationMode = "";
    private int regionSizeChunks;
    // Spawn-path reads (the group-delta resolve runs per spawn), so volatile like raritySpawnChance.
    private volatile double groupDeltaBandWidth;
    private volatile double difficultyMinCap;
    private volatile double difficultyMaxCap;
    // Distance escalation (spawn-path + presence reads, so volatile).
    private volatile boolean distanceEscalationEnabled;
    private volatile double escalationStartDistanceBlocks;
    private volatile double escalationBlocksPerPoint;
    private volatile double escalationMaxBonus;
    private volatile double escalationRarityChancePerPoint;
    // Difficulty stat curve (spawn-path reads, so volatile): how the resolved difficulty maps to a mob's
    // HP / outgoing-damage / incoming-damage-reduction factors, plus each factor's ceiling/floor.
    private volatile double statCurveHpPerPoint;
    private volatile double statCurveOutDamagePerPoint;
    private volatile double statCurveInDamageReductionPerPoint;
    private volatile double statCurveMaxHpMult;
    private volatile double statCurveMaxOutDamageMult;
    private volatile double statCurveMinInDamageMult;
    // HUD settings: read every tick by the HUD system + on install, so all volatile. The enabled flags
    // and positions also take a RUNTIME override from /mobscaling hud (live tuning; lost on restart -
    // the owner file is the persistent authority, and the command says so).
    private volatile boolean zoneHudEnabled;
    @Nonnull private volatile String zoneHudPosition = "";
    private volatile int zoneHudOffsetX;
    private volatile int zoneHudOffsetY;
    private volatile boolean zoneShowLocationName = true;
    // Friendly zone/biome name lang-key prefixes (HUD tick reads; volatile). Zone defaults to the base
    // game's own region-name namespace so "Zone4_Tier5" client-resolves to "Cinder Wastes" for free.
    @Nonnull private volatile String zoneNameKeyPrefix = "server.map.region.";
    @Nonnull private volatile String biomeNameKeyPrefix = "";
    private volatile boolean inspectorHudEnabled;
    @Nonnull private volatile String inspectorHudPosition = "";
    private volatile int inspectorHudOffsetX;
    private volatile int inspectorHudOffsetY;
    private volatile double inspectorRangeBlocks;
    private volatile boolean inspectorPortraitEnabled = true;

    // Per-world settings overlays (1.0.1): the resolved, pre-parsed WorldOverrides matcher entries (rebuilt
    // at every fold, CONCATENATED across jar+preset+owner and deduped by Match), plus a per-world resolved-view
    // cache (cleared on any refold + on setIntensityRuntime). spawnSettingsFor(worldName) reads both.
    @Nonnull private volatile List<WorldOverrideMatcher.Entry> worldOverrideEntries = List.of();
    @Nonnull private final ConcurrentHashMap<String, SpawnScalingSettings> worldViewCache = new ConcurrentHashMap<>();

    private MobScalingConfig() {
    }

    @Nonnull
    public static MobScalingConfig getInstance() {
        if (instance == null) {
            instance = new MobScalingConfig();
        }
        return instance;
    }

    /** Owner override file (typically {@code mods/MmoMobScaling/mob-scaling.json}); {@code null} = defaults only. */
    public void setConfigPath(@Nullable Path configPath) {
        this.configPath = configPath;
    }

    /** The owner override file path a write-back layer targets ({@code null} = defaults only / no path set). */
    @Nullable
    public Path getConfigPath() {
        return configPath;
    }

    /**
     * Load the effective settings: decode the jar Default.json (authoritative defaults) then overlay
     * the owner file (if present). Both via the codec. Fully guarded: a missing/unreadable bundled
     * default fails SAFE (disabled).
     */
    public void load() {
        String rawDefaults = readResource(DEFAULTS_RESOURCE);
        this.jarDefaults = decode(rawDefaults, "jar Default.json");
        if (this.jarDefaults == null) {
            warn("bundled Server/MmoMobScaling/Settings/Default.json missing or unreadable; failing safe (disabled)");
        }
        MobScalingSettingsAsset owner = decode(readOwnerFile(), ownerLabel());
        // Resolve which preset is active from owner-over-jar only (a preset asset never picks the active
        // preset - that would be circular). The store is not loaded yet, so the store layer is null here.
        // NOTE: the synchronous load() reads only the jar Default + owner for the early isEnabled() gate;
        // a preset that flips Enabled needs a RESTART (the gate already fired), same caveat as a pack that
        // overrides Enabled - see the registration-gate note in the router.
        this.activePreset = or(fold3(owner, null, jarDefaults, MobScalingSettingsAsset::getActivePreset), "Default");
        applyFold(jarDefaults, null, owner);
        // AFTER the fold (so this run reads real state, not the freshly-seeded empty file): auto-generate
        // the on-disk config scaffold + the reference schema dump for a fresh install.
        scaffoldConfigFiles(rawDefaults);
    }

    /**
     * Re-apply the settings from the loaded asset STORE (the engine-folded jar + pack presets, keyed by
     * name) over the owner file. Called from the {@code LoadedAssetsEvent} listener AFTER {@code setup()},
     * so a content pack's {@code Server/MmoMobScaling/Settings/*.json} overrides + presets take effect for
     * the runtime-read fields. Captures the whole preset map (for {@link #availablePresetNames()} +
     * {@link #swapActivePreset}), re-resolves the active preset from owner-over-jar, then folds
     * owner > activePresetAsset > jar. Uses the SAME codec + fold as {@link #load()}.
     */
    public void applyStoreLayer(@Nonnull Map<String, MobScalingSettingsAsset> storePresets) {
        this.storePresets = storePresets;
        MobScalingSettingsAsset owner = decode(readOwnerFile(), ownerLabel());
        this.activePreset = or(fold3(owner, null, jarDefaults, MobScalingSettingsAsset::getActivePreset), "Default");
        refoldFromStore(owner);
    }

    /**
     * Backward-compatible overload: fold a SINGLE already-selected settings asset as the store layer
     * (keyed "Default"). Retained for callers/tests that pass one asset rather than the whole preset map;
     * the multi-preset {@link #applyStoreLayer(Map)} is the production path.
     */
    public void applyStoreLayer(@Nonnull MobScalingSettingsAsset storeDefaults) {
        this.storePresets = Map.of("Default", storeDefaults);
        this.activePreset = "Default";
        MobScalingSettingsAsset owner = decode(readOwnerFile(), ownerLabel());
        applyFold(jarDefaults, storeDefaults, owner);
    }

    /**
     * Fold owner > the active-preset store asset > jar. The active preset is selected by
     * {@link #activePreset} from {@link #storePresets}; a missing preset key falls back to the jar Default
     * (store layer null) with a guarded warning. Shared by {@link #applyStoreLayer} + {@link #swapActivePreset}.
     */
    private void refoldFromStore(@Nullable MobScalingSettingsAsset owner) {
        Map<String, MobScalingSettingsAsset> presets = this.storePresets;
        MobScalingSettingsAsset presetAsset = presets == null ? null : lookupPreset(presets, activePreset);
        if (presets != null && presetAsset == null) {
            warnPlugin("active preset '" + activePreset
                    + "' not found in the settings store; folding the jar Default instead");
        }
        applyFold(jarDefaults, presetAsset, owner);
    }

    /**
     * The sorted preset names present in the loaded settings store (e.g. Casual, Default, Hardcore,
     * Playtest); empty until the first {@code LoadedAssetsEvent} populates the store.
     */
    @Nonnull
    public List<String> availablePresetNames() {
        Map<String, MobScalingSettingsAsset> presets = this.storePresets;
        if (presets == null || presets.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>(presets.keySet());
        Collections.sort(names);
        return names;
    }

    /** The preset currently folded as the store layer ("Default" until changed). */
    @Nonnull
    public String getActivePreset() {
        return activePreset;
    }

    /**
     * Switch the active preset and re-fold the runtime settings live from the loaded store (owner still
     * wins over the preset, and partial preset leaves fall through to the jar Default). Returns
     * {@code false} if the name is unknown (store not loaded, or no matching preset) - the current
     * settings are then left unchanged.
     */
    public boolean swapActivePreset(@Nonnull String name) {
        Map<String, MobScalingSettingsAsset> presets = this.storePresets;
        if (presets == null) {
            return false;
        }
        MobScalingSettingsAsset match = lookupPreset(presets, name);
        if (match == null) {
            return false;
        }
        this.activePreset = canonicalName(presets, name);
        MobScalingSettingsAsset owner = decode(readOwnerFile(), ownerLabel());
        applyFold(jarDefaults, match, owner);
        return true;
    }

    /**
     * Re-read the owner override file and re-fold the effective settings IN PLACE, PRESERVING the current
     * in-memory {@link #activePreset} + loaded store (via {@link #refoldFromStore}). The single reconcile a
     * write-back layer ({@code MobScalingOwnerWriter}) calls after persisting a change to
     * {@code mods/MmoMobScaling/mob-scaling.json}, so the live config == the owner file with no restart.
     * Rebuilds {@link #worldOverrideEntries} + clears the per-world view cache (both inside
     * {@link #applyFold}). Safe before the async {@code LoadedAssetsEvent} (store null -> folds
     * owner-over-jar, same as {@link #load()}).
     */
    public void refreshFromDisk() {
        refoldFromStore(decode(readOwnerFile(), ownerLabel()));
    }

    /** The preset asset for {@code name} (exact key first, then case-insensitive); {@code null} if none. */
    @Nullable
    private static MobScalingSettingsAsset lookupPreset(
            @Nonnull Map<String, MobScalingSettingsAsset> presets, @Nonnull String name) {
        MobScalingSettingsAsset exact = presets.get(name);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, MobScalingSettingsAsset> e : presets.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** The store's canonical-case key for {@code name} (so a case-insensitive swap stores the real key). */
    @Nonnull
    private static String canonicalName(
            @Nonnull Map<String, MobScalingSettingsAsset> presets, @Nonnull String name) {
        if (presets.containsKey(name)) {
            return name;
        }
        for (String key : presets.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                return key;
            }
        }
        return name;
    }

    /**
     * Fold {@code owner > store > jar} PER LEAF (a nullable leaf - or its whole nested group - falls to
     * the next layer, then the neutral fail-safe). Folding the JAR layer UNDERNEATH the store means a
     * PARTIAL pack override (wholesale replace) that omits a key inherits the jar value, not the
     * fail-safe - so a pack tuning only {@code RaritySpawnChance} can never accidentally fold
     * {@code enabled} to {@code false} and silently kill the mod at runtime.
     */
    private void applyFold(@Nullable MobScalingSettingsAsset jar, @Nullable MobScalingSettingsAsset store,
            @Nullable MobScalingSettingsAsset owner) {
        this.enabled = or(fold3(owner, store, jar, MobScalingSettingsAsset::getEnabled), false);
        this.presetMode = or(fold3(owner, store, jar, MobScalingSettingsAsset::getPresetMode), "");
        // Intensity is now a numeric multiplier (1.0.1); neutral fallback 1.0, clamped >= 0.
        this.intensity = Math.max(0.0, or(fold3(owner, store, jar, MobScalingSettingsAsset::getIntensity), 1.0));
        double chance = or(fold3(owner, store, jar, MobScalingSettingsAsset::getRaritySpawnChance), 0.0);
        this.raritySpawnChance = Math.max(0.0, Math.min(1.0, chance)); // clamp: an unclamped chance is a footgun

        // OpenWorld group (nested).
        this.openWorldAggregationMode = or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getOpenWorld, OpenWorld::getAggregationMode), "");
        this.regionSizeChunks = or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getOpenWorld, OpenWorld::getRegionSizeChunks), 0);
        double band = or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getOpenWorld, OpenWorld::getGroupDeltaBandWidth), 0.0);
        this.groupDeltaBandWidth = Math.max(0.0, band); // the engine expects a non-negative band
        this.allowDifficultyIncreaseOnPartyJoin = or(fold3(owner, store, jar,
                MobScalingSettingsAsset::getOpenWorld, OpenWorld::getAllowDifficultyIncreaseOnPartyJoin), false);
        this.lateArrivalBumpFactor = or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getOpenWorld, OpenWorld::getLateArrivalBumpFactor), 0.0);
        this.compositionEnabled = or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getOpenWorld, OpenWorld::getCompositionEnabled), false);
        this.onlyRaiseDifficulty = or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getOpenWorld, OpenWorld::getOnlyRaiseDifficulty), true);
        this.playerScalingEnabled = or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getOpenWorld, OpenWorld::getPlayerScalingEnabled), true);

        // Difficulty group (nested; caps + the doubly-nested distance escalation).
        this.difficultyMinCap = or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getDifficulty, Difficulty::getMinCap), 0.0);
        double maxCap = or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getDifficulty, Difficulty::getMaxCap), 0.0);
        this.difficultyMaxCap = Math.max(this.difficultyMinCap, maxCap); // an inverted cap pair is a footgun
        this.distanceEscalationEnabled = or(
                fold3(owner, store, jar, MobScalingConfig::escalation, DistanceEscalation::getEnabled), false);
        this.escalationStartDistanceBlocks = Math.max(0.0, or(
                fold3(owner, store, jar, MobScalingConfig::escalation, DistanceEscalation::getStartDistanceBlocks), 0.0));
        double blocksPerPoint = or(
                fold3(owner, store, jar, MobScalingConfig::escalation, DistanceEscalation::getBlocksPerPoint), 0.0);
        this.escalationBlocksPerPoint = Math.max(1.0, blocksPerPoint); // a zero divisor is a footgun
        this.escalationMaxBonus = Math.max(0.0, or(
                fold3(owner, store, jar, MobScalingConfig::escalation, DistanceEscalation::getMaxBonus), 0.0));
        this.escalationRarityChancePerPoint = Math.max(0.0, or(
                fold3(owner, store, jar, MobScalingConfig::escalation, DistanceEscalation::getRarityChancePerPoint), 0.0));

        // Difficulty stat curve (doubly-nested under Difficulty). NEUTRAL broken-jar fallbacks build the
        // IDENTITY curve (zero slopes + the legacy MobScaleFold caps), so a broken-but-enabled jar behaves
        // like the old rarity-only fold rather than flattening every mob's stats.
        this.statCurveHpPerPoint = Math.max(0.0,
                or(fold3(owner, store, jar, MobScalingConfig::statCurve, StatCurve::getHpPerPoint), 0.0));
        this.statCurveOutDamagePerPoint = Math.max(0.0,
                or(fold3(owner, store, jar, MobScalingConfig::statCurve, StatCurve::getOutDamagePerPoint), 0.0));
        this.statCurveInDamageReductionPerPoint = Math.max(0.0,
                or(fold3(owner, store, jar, MobScalingConfig::statCurve, StatCurve::getInDamageReductionPerPoint), 0.0));
        this.statCurveMaxHpMult = Math.max(1.0,
                or(fold3(owner, store, jar, MobScalingConfig::statCurve, StatCurve::getMaxHpMult), MobScaleFold.MAX_HEALTH_MULT));
        this.statCurveMaxOutDamageMult = Math.max(1.0,
                or(fold3(owner, store, jar, MobScalingConfig::statCurve, StatCurve::getMaxOutDamageMult), MobScaleFold.OUT_DMG_MAX));
        double statCurveMinIn = or(
                fold3(owner, store, jar, MobScalingConfig::statCurve, StatCurve::getMinInDamageMult), MobScaleFold.IN_DMG_MIN);
        this.statCurveMinInDamageMult = Math.max(0.01, Math.min(1.0, statCurveMinIn)); // minIn in (0,1]

        // HUD groups (nested).
        this.zoneHudEnabled = or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getZoneHud, Hud::getEnabled), false);
        this.zoneHudPosition = or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getZoneHud, Hud::getPosition), "");
        this.zoneHudOffsetX = or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getZoneHud, Hud::getOffsetX), 0);
        this.zoneHudOffsetY = or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getZoneHud, Hud::getOffsetY), 0);
        this.zoneShowLocationName = or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getZoneHud, Hud::getShowLocationName), true);
        this.zoneNameKeyPrefix = or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getZoneHud, Hud::getZoneNameKeyPrefix),
                "server.map.region.");
        this.biomeNameKeyPrefix = or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getZoneHud, Hud::getBiomeNameKeyPrefix), "");
        this.inspectorHudEnabled = or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getInspectorHud, InspectorHud::getEnabled), false);
        this.inspectorHudPosition = or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getInspectorHud, InspectorHud::getPosition), "");
        this.inspectorHudOffsetX = or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getInspectorHud, InspectorHud::getOffsetX), 0);
        this.inspectorHudOffsetY = or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getInspectorHud, InspectorHud::getOffsetY), 0);
        double range = or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getInspectorHud, InspectorHud::getRangeBlocks), 0.0);
        this.inspectorRangeBlocks = Math.max(2.0, Math.min(32.0, range)); // sane raycast bounds
        this.inspectorPortraitEnabled = or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getInspectorHud, InspectorHud::getPortraitEnabled), true);

        // Per-world overlays (1.0.1): CONCATENATE across layers + dedup by Match (owner > preset > jar wins a
        // collision), then drop the resolved-view cache so the next spawn re-resolves against the fresh set.
        this.worldOverrideEntries = foldWorldOverrides(jar, store, owner);
        this.worldViewCache.clear();
    }

    /** The doubly-nested escalation group ({@code Difficulty.DistanceEscalation}); {@code null} when absent. */
    @Nullable
    private static DistanceEscalation escalation(@Nonnull MobScalingSettingsAsset a) {
        Difficulty d = a.getDifficulty();
        return d == null ? null : d.getDistanceEscalation();
    }

    /** The doubly-nested stat-curve group ({@code Difficulty.StatCurve}); {@code null} when absent. */
    @Nullable
    private static StatCurve statCurve(@Nonnull MobScalingSettingsAsset a) {
        Difficulty d = a.getDifficulty();
        return d == null ? null : d.getStatCurve();
    }

    /**
     * Fold the per-world overlays (1.0.1) by CONCATENATION, not the whole-array replace the generic leaf fold
     * uses: gather jar -> preset -> owner entries into a {@code LinkedHashMap} keyed by lower-cased {@code Match}
     * so a LATER layer wins a same-{@code Match} collision (owner > preset > jar) while distinct patterns from
     * every layer coexist. So an owner file ADDS to / overrides the jar-shipped dungeon defaults rather than
     * replacing the whole list. Blank-{@code Match} entries are dropped. Returns pre-parsed matcher entries.
     */
    @Nonnull
    private static List<WorldOverrideMatcher.Entry> foldWorldOverrides(@Nullable MobScalingSettingsAsset jar,
            @Nullable MobScalingSettingsAsset store, @Nullable MobScalingSettingsAsset owner) {
        LinkedHashMap<String, WorldOverride> byMatch = new LinkedHashMap<>();
        collectOverrides(byMatch, jar);
        collectOverrides(byMatch, store);
        collectOverrides(byMatch, owner);
        if (byMatch.isEmpty()) {
            return List.of();
        }
        List<WorldOverrideMatcher.Entry> entries = new ArrayList<>(byMatch.size());
        for (WorldOverride ov : byMatch.values()) {
            String match = ov.getMatch();
            entries.add(new WorldOverrideMatcher.Entry(match.trim(), ov)); // getMatch non-blank (collect filters)
        }
        return entries;
    }

    /** Add one layer's non-blank-{@code Match} overrides into the dedup map (later put wins the same key). */
    private static void collectOverrides(@Nonnull LinkedHashMap<String, WorldOverride> byMatch,
            @Nullable MobScalingSettingsAsset asset) {
        if (asset == null) {
            return;
        }
        WorldOverride[] arr = asset.getWorldOverrides();
        if (arr == null) {
            return;
        }
        for (WorldOverride ov : arr) {
            if (ov == null) {
                continue;
            }
            String match = ov.getMatch();
            if (match == null || match.isBlank()) {
                continue;
            }
            byMatch.put(match.trim().toLowerCase(Locale.ROOT), ov);
        }
    }

    // ---------------------------------------------------------------------
    // Leaf fold: first non-null across owner > store > jar
    // ---------------------------------------------------------------------

    /** Top-level leaf: the first non-null value across the three layers; {@code null} when all absent. */
    @Nullable
    private static <T> T fold3(@Nullable MobScalingSettingsAsset owner, @Nullable MobScalingSettingsAsset store,
            @Nullable MobScalingSettingsAsset jar, @Nonnull Function<MobScalingSettingsAsset, T> leaf) {
        T v = leafOf(owner, leaf);
        if (v != null) return v;
        v = leafOf(store, leaf);
        if (v != null) return v;
        return leafOf(jar, leaf);
    }

    /** Nested leaf: walks {@code asset -> group -> leaf} per layer; an absent group reads as an absent leaf. */
    @Nullable
    private static <G, T> T fold3(@Nullable MobScalingSettingsAsset owner, @Nullable MobScalingSettingsAsset store,
            @Nullable MobScalingSettingsAsset jar, @Nonnull Function<MobScalingSettingsAsset, G> group,
            @Nonnull Function<G, T> leaf) {
        return fold3(owner, store, jar, a -> {
            G g = group.apply(a);
            return g == null ? null : leaf.apply(g);
        });
    }

    @Nullable
    private static <T> T leafOf(@Nullable MobScalingSettingsAsset a,
            @Nonnull Function<MobScalingSettingsAsset, T> leaf) {
        return a == null ? null : leaf.apply(a);
    }

    private static boolean or(@Nullable Boolean v, boolean fb) { return v != null ? v : fb; }
    private static double or(@Nullable Double v, double fb) { return v != null ? v : fb; }
    private static int or(@Nullable Integer v, int fb) { return v != null ? v : fb; }
    @Nonnull private static String or(@Nullable String v, @Nonnull String fb) { return v != null ? v : fb; }

    // ---------------------------------------------------------------------
    // Codec decode (synchronous)
    // ---------------------------------------------------------------------

    /**
     * Decode a settings body via the codec; {@code null} for a null/blank body. A present-but-malformed body
     * warns (attributed to {@code sourceLabel}) so a broken owner file is not swallowed silently.
     */
    @Nullable
    private static MobScalingSettingsAsset decode(@Nullable String body, @Nonnull String sourceLabel) {
        if (body == null || body.isBlank()) {
            return null; // absent = normal (defaults only); not a warning
        }
        try {
            return MobScalingSettingsAsset.CODEC.decodeJson(RawJsonReader.fromJsonString(body), new ExtraInfo());
        } catch (Exception e) {
            warn(sourceLabel + " is malformed and was IGNORED (jar/pack defaults apply): " + e.getMessage());
            return null;
        }
    }

    @Nonnull
    private String ownerLabel() {
        return configPath != null ? configPath.toString() : "mods/MmoMobScaling/mob-scaling.json";
    }

    /** Guarded warn (own logger; must not sink to MobScalingPlugin, which is unloadable in a unit JVM). */
    private static void warn(@Nonnull String message) {
        if (LOGGER == null) {
            return;
        }
        try {
            LOGGER.atWarning().log("[MobScalingConfig] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM
        }
    }

    /** Guarded info (own logger; same log-manager-less-JVM guard as {@link #warn}). */
    private static void info(@Nonnull String message) {
        if (LOGGER == null) {
            return;
        }
        try {
            LOGGER.atInfo().log("[MobScalingConfig] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM
        }
    }

    /**
     * Guarded warn through {@code MobScalingPlugin.LOGGER}, for the RUNTIME-only paths (preset fold on
     * {@code LoadedAssetsEvent} / {@code /mobscaling preset}) that never run in a unit JVM. The
     * try/catch(Throwable) keeps a NoClassDefFoundError / log-manager-less JVM from escaping, so it stays
     * safe even if reached off the main path.
     */
    private static void warnPlugin(@Nonnull String message) {
        try {
            MobScalingPlugin.LOGGER.atWarning().log("[MobScalingConfig] " + message);
        } catch (Throwable ignored) {
            // MobScalingPlugin unloadable in a unit JVM, or log-manager-less
        }
    }

    /** Read the jar-bundled default asset off the classpath; {@code null} on any error (broken jar). */
    @Nullable
    private static String readResource(@Nonnull String resource) {
        try (InputStream in = MobScalingConfig.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Auto-generate the on-disk config scaffold for a fresh install (mirrors the MMO jar's
     * {@code AbstractOverrideConfig}): ensure {@code mods/MmoMobScaling/} exists, (re)write
     * {@code _reference/}{@value #REFERENCE_FILE} = the jar-bundled {@code Default.json} verbatim (the full
     * schema an owner can read + copy from, refreshed every load so it tracks the jar), and seed an EMPTY
     * {@code mob-scaling.json} owner file ONLY when it does not already exist (never clobber admin edits;
     * an empty {@code {}} overrides nothing, so defaults still apply). Fully guarded - a write failure only
     * warns and never breaks the {@code setup()}-time registration gate. No-op when {@link #configPath} is
     * null (defaults-only / unit tests).
     */
    private void scaffoldConfigFiles(@Nullable String rawDefaults) {
        Path owner = this.configPath;
        if (owner == null) {
            return;
        }
        Path dir = owner.getParent();
        try {
            if (dir != null) {
                Files.createDirectories(dir);
                if (rawDefaults != null && !rawDefaults.isBlank()) {
                    Path refDir = dir.resolve("_reference");
                    Files.createDirectories(refDir);
                    Files.writeString(refDir.resolve(REFERENCE_FILE), rawDefaults, StandardCharsets.UTF_8);
                }
            }
            if (!Files.exists(owner)) {
                Files.writeString(owner, OWNER_SCAFFOLD, StandardCharsets.UTF_8);
                info("first run: wrote an empty override scaffold at " + owner
                        + " - edit it to override defaults; the full schema is in _reference/" + REFERENCE_FILE);
            }
        } catch (Exception e) {
            warn("could not write the config scaffold (" + owner + "): " + e.getMessage());
        }
    }

    /** Read the owner override file if a path is set and it exists; {@code null} otherwise. */
    @Nullable
    private String readOwnerFile() {
        Path path = this.configPath;
        if (path == null) {
            return null;
        }
        try {
            if (!Files.exists(path)) {
                return null;
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Getters
    // ---------------------------------------------------------------------

    public boolean isEnabled() { return enabled; }
    public boolean isCompositionEnabled() { return compositionEnabled; }
    @Override public boolean isOnlyRaiseDifficulty() { return onlyRaiseDifficulty; }
    @Override public boolean isPlayerScalingEnabled() { return playerScalingEnabled; }
    @Nonnull public String getPresetMode() { return presetMode; }
    /** The GLOBAL intensity multiplier on the stat-curve slopes (1.0.1; default 1.0). */
    public double getIntensity() { return intensity; }
    @Override public double getRaritySpawnChance() { return raritySpawnChance; }
    public boolean isAllowDifficultyIncreaseOnPartyJoin() { return allowDifficultyIncreaseOnPartyJoin; }
    public double getLateArrivalBumpFactor() { return lateArrivalBumpFactor; }
    @Nonnull public String getOpenWorldAggregationMode() { return openWorldAggregationMode; }
    public int getRegionSizeChunks() { return regionSizeChunks; }
    public double getGroupDeltaBandWidth() { return groupDeltaBandWidth; }
    public double getDifficultyMinCap() { return difficultyMinCap; }
    public double getDifficultyMaxCap() { return difficultyMaxCap; }
    public boolean isDistanceEscalationEnabled() { return distanceEscalationEnabled; }
    public double getEscalationStartDistanceBlocks() { return escalationStartDistanceBlocks; }
    public double getEscalationBlocksPerPoint() { return escalationBlocksPerPoint; }
    public double getEscalationMaxBonus() { return escalationMaxBonus; }
    public double getEscalationRarityChancePerPoint() { return escalationRarityChancePerPoint; }
    public double getStatCurveHpPerPoint() { return statCurveHpPerPoint; }
    public double getStatCurveOutDamagePerPoint() { return statCurveOutDamagePerPoint; }
    public double getStatCurveInDamageReductionPerPoint() { return statCurveInDamageReductionPerPoint; }
    public double getStatCurveMaxHpMult() { return statCurveMaxHpMult; }
    public double getStatCurveMaxOutDamageMult() { return statCurveMaxOutDamageMult; }
    public double getStatCurveMinInDamageMult() { return statCurveMinInDamageMult; }

    /**
     * Build the GLOBAL difficulty stat curve from the folded leaves, with the {@code Intensity} multiplier
     * (1.0.1) applied to the three slopes. A per-world overlay builds its own curve in
     * {@link ResolvedWorldSettings#statCurveModel()} with the effective per-world intensity + slopes.
     */
    @Nonnull
    @Override
    public MobScaleFold.DifficultyStatCurve statCurveModel() {
        return buildCurve(statCurveHpPerPoint, statCurveOutDamagePerPoint, statCurveInDamageReductionPerPoint,
                statCurveMaxHpMult, statCurveMaxOutDamageMult, statCurveMinInDamageMult, intensity);
    }

    /**
     * Build a {@link MobScaleFold.DifficultyStatCurve}, multiplying the three SLOPES by {@code intensity}
     * (clamped {@code >= 0}); the caps are unchanged (the curve's own per-factor ceilings still bound the
     * result). Shared by the global {@link #statCurveModel()} and the per-world overlay view.
     */
    @Nonnull
    private static MobScaleFold.DifficultyStatCurve buildCurve(double hpPerPoint, double outPerPoint,
            double inReductionPerPoint, double maxHpMult, double maxOutDamageMult, double minInDamageMult,
            double intensity) {
        double k = Math.max(0.0, intensity);
        return new MobScaleFold.DifficultyStatCurve(hpPerPoint * k, outPerPoint * k, inReductionPerPoint * k,
                maxHpMult, maxOutDamageMult, minInDamageMult);
    }

    public boolean isZoneHudEnabled() { return zoneHudEnabled; }
    public boolean isZoneShowLocationName() { return zoneShowLocationName; }
    @Nonnull public String getZoneHudPosition() { return zoneHudPosition; }
    public int getZoneHudOffsetX() { return zoneHudOffsetX; }
    public int getZoneHudOffsetY() { return zoneHudOffsetY; }
    @Nonnull public String getZoneNameKeyPrefix() { return zoneNameKeyPrefix; }
    @Nonnull public String getBiomeNameKeyPrefix() { return biomeNameKeyPrefix; }
    public boolean isInspectorHudEnabled() { return inspectorHudEnabled; }
    @Nonnull public String getInspectorHudPosition() { return inspectorHudPosition; }
    public int getInspectorHudOffsetX() { return inspectorHudOffsetX; }
    public int getInspectorHudOffsetY() { return inspectorHudOffsetY; }
    public double getInspectorRangeBlocks() { return inspectorRangeBlocks; }
    public boolean isInspectorPortraitEnabled() { return inspectorPortraitEnabled; }

    // ---------------------------------------------------------------------
    // Runtime HUD overrides (/mobscaling hud - live tuning only)
    // ---------------------------------------------------------------------
    // These mutate the folded runtime value directly and are LOST on restart or on the next
    // applyStoreLayer refold; the owner file (mods/MmoMobScaling/mob-scaling.json) is the
    // persistent authority and the command reminds the admin of that.

    public void setZoneHudEnabledRuntime(boolean value) { this.zoneHudEnabled = value; }
    public void setZoneShowLocationNameRuntime(boolean value) { this.zoneShowLocationName = value; }
    public void setInspectorHudEnabledRuntime(boolean value) { this.inspectorHudEnabled = value; }

    public void setZoneHudPositionRuntime(@Nonnull String position, int offsetX, int offsetY) {
        this.zoneHudPosition = position;
        this.zoneHudOffsetX = offsetX;
        this.zoneHudOffsetY = offsetY;
    }

    public void setInspectorHudPositionRuntime(@Nonnull String position, int offsetX, int offsetY) {
        this.inspectorHudPosition = position;
        this.inspectorHudOffsetX = offsetX;
        this.inspectorHudOffsetY = offsetY;
    }

    /**
     * Live-tune the GLOBAL {@code Intensity} multiplier ({@code /mobscaling intensity}). RUNTIME-ONLY: lost
     * on restart or the next {@code applyStoreLayer} refold; the owner file's {@code Intensity} is the
     * persistent authority (the command reminds the admin). A world with an AUTHORED per-world
     * {@code Intensity} override is UNAFFECTED (authoring wins). Clamped {@code >= 0}; clears the per-world
     * view cache so the effective per-world curves re-resolve against the new global.
     */
    public void setIntensityRuntime(double value) {
        this.intensity = Math.max(0.0, value);
        this.worldViewCache.clear();
    }

    // ---------------------------------------------------------------------
    // Per-world settings overlay (1.0.1)
    // ---------------------------------------------------------------------

    /**
     * The effective spawn-time settings for {@code worldName}: the GLOBAL config itself when no
     * {@code WorldOverride} matches (zero-alloc common case), else a cached {@link ResolvedWorldSettings}
     * overlay where every exposed leaf is {@code override-leaf ?? global}. The cache is dropped on any refold
     * ({@link #applyFold}) and on {@link #setIntensityRuntime}, so a reload / preset swap / intensity change
     * takes effect on the next spawn.
     */
    @Nonnull
    public SpawnScalingSettings spawnSettingsFor(@Nullable String worldName) {
        if (worldName == null || worldName.isEmpty() || this.worldOverrideEntries.isEmpty()) {
            return this;
        }
        return worldViewCache.computeIfAbsent(worldName, this::resolveView);
    }

    @Nonnull
    private SpawnScalingSettings resolveView(@Nonnull String worldName) {
        WorldOverride ov = WorldOverrideMatcher.resolve(this.worldOverrideEntries, worldName);
        return ov == null ? this : new ResolvedWorldSettings(this, ov);
    }

    /**
     * The FOLDED per-world overrides (jar + preset + owner, concatenated + deduped by {@code Match}), in
     * fold order - each an effective {@link WorldOverride} whose leaves the admin UI renders + pre-fills an
     * edit from. Empty when no overrides are authored on any layer.
     */
    @Nonnull
    public List<WorldOverride> worldOverrideView() {
        List<WorldOverrideMatcher.Entry> entries = this.worldOverrideEntries;
        if (entries.isEmpty()) {
            return List.of();
        }
        List<WorldOverride> out = new ArrayList<>(entries.size());
        for (WorldOverrideMatcher.Entry e : entries) {
            out.add(e.override);
        }
        return out;
    }

    /**
     * The FOLDED override whose authored {@code Match} equals {@code match} (case-insensitive), or
     * {@code null} if none. Pre-fills the admin editor from the effective entry so editing a jar-shipped
     * default carries its leaves forward (the fold replaces a {@code WorldOverride} by {@code Match} WHOLE,
     * so an omitted leaf would otherwise be lost). Matches by the AUTHORED pattern, not by world resolution.
     */
    @Nullable
    public WorldOverride effectiveWorldOverride(@Nullable String match) {
        if (match == null || match.isBlank()) {
            return null;
        }
        String want = match.trim().toLowerCase(Locale.ROOT);
        for (WorldOverrideMatcher.Entry e : this.worldOverrideEntries) {
            String m = e.override.getMatch();
            if (m != null && m.trim().toLowerCase(Locale.ROOT).equals(want)) {
                return e.override;
            }
        }
        return null;
    }

    /**
     * A per-world overlay view over the global config: every EXPOSED leaf is {@code override-leaf ?? global},
     * and {@link #statCurveModel()} multiplies the resolved slopes by the effective intensity
     * ({@code override.Intensity ?? global}). The un-exposed getters (region size, group band, only-raise,
     * aggregation) delegate straight to the global config, so {@code RegionSizeChunks} stays global (region
     * grid consistency). Immutable + stateless beyond its two references, safe to cache + read cross-thread.
     */
    private static final class ResolvedWorldSettings implements SpawnScalingSettings {
        @Nonnull private final MobScalingConfig g;
        @Nonnull private final WorldOverride ov;

        ResolvedWorldSettings(@Nonnull MobScalingConfig g, @Nonnull WorldOverride ov) {
            this.g = g;
            this.ov = ov;
        }

        @Nullable private DistanceEscalation esc() {
            Difficulty d = ov.getDifficulty();
            return d == null ? null : d.getDistanceEscalation();
        }

        @Override public double getRaritySpawnChance() {
            Double v = ov.getRaritySpawnChance();
            return v != null ? Math.max(0.0, Math.min(1.0, v)) : g.getRaritySpawnChance();
        }

        @Override public boolean isDistanceEscalationEnabled() {
            DistanceEscalation e = esc();
            return e != null && e.getEnabled() != null ? e.getEnabled() : g.isDistanceEscalationEnabled();
        }

        @Override public double getEscalationStartDistanceBlocks() {
            DistanceEscalation e = esc();
            return e != null && e.getStartDistanceBlocks() != null
                    ? Math.max(0.0, e.getStartDistanceBlocks()) : g.getEscalationStartDistanceBlocks();
        }

        @Override public double getEscalationBlocksPerPoint() {
            DistanceEscalation e = esc();
            return e != null && e.getBlocksPerPoint() != null
                    ? Math.max(1.0, e.getBlocksPerPoint()) : g.getEscalationBlocksPerPoint();
        }

        @Override public double getEscalationMaxBonus() {
            DistanceEscalation e = esc();
            return e != null && e.getMaxBonus() != null
                    ? Math.max(0.0, e.getMaxBonus()) : g.getEscalationMaxBonus();
        }

        @Override public double getEscalationRarityChancePerPoint() {
            DistanceEscalation e = esc();
            return e != null && e.getRarityChancePerPoint() != null
                    ? Math.max(0.0, e.getRarityChancePerPoint()) : g.getEscalationRarityChancePerPoint();
        }

        @Override public double getDifficultyMinCap() {
            Difficulty d = ov.getDifficulty();
            return d != null && d.getMinCap() != null ? d.getMinCap() : g.getDifficultyMinCap();
        }

        @Override public double getDifficultyMaxCap() {
            double min = getDifficultyMinCap();
            Difficulty d = ov.getDifficulty();
            double max = d != null && d.getMaxCap() != null ? d.getMaxCap() : g.getDifficultyMaxCap();
            return Math.max(min, max); // an inverted cap pair is a footgun
        }

        @Override public int getRegionSizeChunks() { return g.getRegionSizeChunks(); }
        @Override public double getGroupDeltaBandWidth() { return g.getGroupDeltaBandWidth(); }
        @Override public boolean isOnlyRaiseDifficulty() { return g.isOnlyRaiseDifficulty(); }

        @Override public boolean isPlayerScalingEnabled() {
            Boolean v = ov.getPlayerScalingEnabled();
            return v != null ? v : g.isPlayerScalingEnabled();
        }

        @Nonnull @Override public String getOpenWorldAggregationMode() { return g.getOpenWorldAggregationMode(); }

        @Nonnull
        @Override
        public MobScaleFold.DifficultyStatCurve statCurveModel() {
            StatCurve c = ov.getDifficulty() == null ? null : ov.getDifficulty().getStatCurve();
            double hp = c != null && c.getHpPerPoint() != null
                    ? Math.max(0.0, c.getHpPerPoint()) : g.statCurveHpPerPoint;
            double out = c != null && c.getOutDamagePerPoint() != null
                    ? Math.max(0.0, c.getOutDamagePerPoint()) : g.statCurveOutDamagePerPoint;
            double in = c != null && c.getInDamageReductionPerPoint() != null
                    ? Math.max(0.0, c.getInDamageReductionPerPoint()) : g.statCurveInDamageReductionPerPoint;
            double maxHp = c != null && c.getMaxHpMult() != null
                    ? Math.max(1.0, c.getMaxHpMult()) : g.statCurveMaxHpMult;
            double maxOut = c != null && c.getMaxOutDamageMult() != null
                    ? Math.max(1.0, c.getMaxOutDamageMult()) : g.statCurveMaxOutDamageMult;
            double minIn = c != null && c.getMinInDamageMult() != null
                    ? Math.max(0.01, Math.min(1.0, c.getMinInDamageMult())) : g.statCurveMinInDamageMult;
            double eff = ov.getIntensity() != null ? Math.max(0.0, ov.getIntensity()) : g.intensity;
            return buildCurve(hp, out, in, maxHp, maxOut, minIn, eff);
        }
    }
}
