package com.ziggfreed.mmomobscaling.config;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
import com.ziggfreed.mmomobscaling.scaling.MobScaleFold;

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
public final class MobScalingConfig {

    /** Jar-bundled authoritative defaults, decoded via the codec (classpath resource). */
    private static final String DEFAULTS_RESOURCE = "/Server/MmoMobScaling/Settings/Default.json";

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
    @Nonnull private String presetMode = "";
    @Nonnull private String intensity = "";
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

    /**
     * Load the effective settings: decode the jar Default.json (authoritative defaults) then overlay
     * the owner file (if present). Both via the codec. Fully guarded: a missing/unreadable bundled
     * default fails SAFE (disabled).
     */
    public void load() {
        this.jarDefaults = decode(readResource(DEFAULTS_RESOURCE), "jar Default.json");
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
        this.intensity = or(fold3(owner, store, jar, MobScalingSettingsAsset::getIntensity), "");
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
    public boolean isOnlyRaiseDifficulty() { return onlyRaiseDifficulty; }
    @Nonnull public String getPresetMode() { return presetMode; }
    @Nonnull public String getIntensity() { return intensity; }
    public double getRaritySpawnChance() { return raritySpawnChance; }
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

    /** Build the difficulty stat curve ({@code hp/out/inReduction} slopes + their ceilings) from the folded leaves. */
    @Nonnull
    public MobScaleFold.DifficultyStatCurve statCurveModel() {
        return new MobScaleFold.DifficultyStatCurve(statCurveHpPerPoint, statCurveOutDamagePerPoint,
                statCurveInDamageReductionPerPoint, statCurveMaxHpMult, statCurveMaxOutDamageMult, statCurveMinInDamageMult);
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
}
