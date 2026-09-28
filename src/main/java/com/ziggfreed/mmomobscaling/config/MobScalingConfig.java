package com.ziggfreed.mmomobscaling.config;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.Clamps;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.Difficulty;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.DistanceEscalation;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.EscalationOrigin;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.Hud;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.InspectorHud;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.OpenWorld;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.StatCurve;
import com.ziggfreed.mmomobscaling.asset.WorldSettings;
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
 * a broken jar is missing its bundled default asset: the identity curve and rails that never bind).
 */
public final class MobScalingConfig implements SpawnScalingSettings {

    /** Jar-bundled authoritative defaults, decoded via the codec (classpath resource). */
    private static final String DEFAULTS_RESOURCE = "/Server/MmoMobScaling/Settings/Default.json";

    /** The reference dump filename written under {@code mods/MmoMobScaling/_reference/}. */
    private static final String REFERENCE_FILE = "defaults-mob-scaling.json";

    /**
     * Per-world rules folder beside the owner file. Named here only for the boot INFO line;
     * {@code WorldSettingsConfig} owns the real path (set from {@code MobScalingPlugin.setup}).
     */
    private static final String WORLDS_DIR = "worlds";

    /**
     * Per-mapping zone/biome floor overrides beside the owner file. Named here only for the boot INFO
     * line; {@code DifficultyOwnerLayer} owns the real path (set from {@code MobScalingPlugin.setup}).
     */
    private static final String DIFFICULTY_DIR = "difficulty";

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
    // Group-power delta may only raise a region's difficulty over the floor, never soften it. Spawn-path read.
    private volatile boolean onlyRaiseDifficulty = true;
    // Whether player/group-based scaling applies at all (default on). Spawn-path read (volatile);
    // a world with PlayerScalingEnabled=false pins difficulty to the escalated floor.
    private volatile boolean playerScalingEnabled = true;
    // Protected radius around world spawn inside which the group delta never applies (its OWN knob, NOT the
    // distance-escalation start radius). Spawn-path read, so volatile. 0 = no protected ring.
    private volatile double playerScalingStartRingBlocks;
    private volatile double raritySpawnChance;
    @Nonnull private String openWorldAggregationMode = "";
    private int regionSizeChunks;
    // Spawn-path reads (the group-delta resolve runs per spawn), so volatile like raritySpawnChance.
    private volatile double groupDeltaBandWidth;
    private volatile double difficultyMinCap;
    private volatile double difficultyMaxCap;
    // The world-baseline difficulty floor: the lowest-precedence floor under the zone/biome
    // Difficulty/*.json mappings. Spawn-path.
    private volatile double difficultyFloor;
    // Distance escalation (spawn-path + presence reads, so volatile).
    private volatile boolean distanceEscalationEnabled;
    // The authored escalation origin, per axis; null = unset at every layer, and the resolver then reads
    // the world's own spawn point on that axis. There is no fail-safe number here on purpose: no number
    // means "the spawn point", so null IS the default every server ships with.
    @Nullable private volatile Double escalationOriginX;
    @Nullable private volatile Double escalationOriginZ;
    private volatile double escalationStartDistanceBlocks;
    private volatile double escalationBlocksPerPoint;
    private volatile double escalationMaxBonus;
    private volatile double escalationRarityChancePerPoint;
    // The difficulty stat curve and the safety clamps, folded per leaf and rebuilt as the two immutable
    // records the fold reads (spawn-path reads, so the records are volatile).
    @Nonnull private volatile MobScaleFold.DifficultyStatCurve statCurve = MobScaleFold.DifficultyStatCurve.NONE;
    @Nonnull private volatile MobScaleFold.Clamps clamps = MobScaleFold.Clamps.NONE;
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

    // Per-world resolved-view cache: worldName -> ResolvedWorldSettings overlay (or this on no
    // match). The rules themselves live in WorldSettingsConfig (Worlds/*.json, Parent-merged); this cache
    // is cleared on any refold (global OR worlds) and by invalidateWorldViews().
    @Nonnull private final ConcurrentHashMap<String, SpawnScalingSettings> worldViewCache = new ConcurrentHashMap<>();
    // The same cache for a lookup made from a world NAME alone, which can score fewer axes (no
    // GameplayConfig) and so must never share an entry with the full-world lookup.
    @Nonnull private final ConcurrentHashMap<String, SpawnScalingSettings> nameOnlyViewCache = new ConcurrentHashMap<>();

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
     * Clears the per-world view cache (inside {@link #applyFold}). Safe before the async
     * {@code LoadedAssetsEvent} (store null -> folds owner-over-jar, same as {@link #load()}).
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
        this.onlyRaiseDifficulty = or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getOpenWorld, OpenWorld::getOnlyRaiseDifficulty), true);
        this.playerScalingEnabled = or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getOpenWorld, OpenWorld::getPlayerScalingEnabled), true);
        this.playerScalingStartRingBlocks = Math.max(0.0, or(fold3(owner, store, jar,
                MobScalingSettingsAsset::getOpenWorld, OpenWorld::getPlayerScalingStartRingBlocks), 0.0));

        // Difficulty group (nested; world-baseline floor + caps + the doubly-nested distance escalation).
        this.difficultyFloor = Math.max(0.0, or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getDifficulty, Difficulty::getFloor), 0.0));
        this.difficultyMinCap = or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getDifficulty, Difficulty::getMinCap), 0.0);
        double maxCap = or(
                fold3(owner, store, jar, MobScalingSettingsAsset::getDifficulty, Difficulty::getMaxCap), 0.0);
        this.difficultyMaxCap = Math.max(this.difficultyMinCap, maxCap); // an inverted cap pair is a footgun
        this.distanceEscalationEnabled = or(
                fold3(owner, store, jar, MobScalingConfig::escalation, DistanceEscalation::getEnabled), false);
        // The origin folds per axis with NO default: an axis unset at every layer stays null (the spawn point).
        this.escalationOriginX = fold3(owner, store, jar, MobScalingConfig::escalationOrigin, EscalationOrigin::getX);
        this.escalationOriginZ = fold3(owner, store, jar, MobScalingConfig::escalationOrigin, EscalationOrigin::getZ);
        this.escalationStartDistanceBlocks = Math.max(0.0, or(
                fold3(owner, store, jar, MobScalingConfig::escalation, DistanceEscalation::getStartDistanceBlocks), 0.0));
        double blocksPerPoint = or(
                fold3(owner, store, jar, MobScalingConfig::escalation, DistanceEscalation::getBlocksPerPoint), 0.0);
        this.escalationBlocksPerPoint = Math.max(1.0, blocksPerPoint); // a zero divisor is a footgun
        this.escalationMaxBonus = Math.max(0.0, or(
                fold3(owner, store, jar, MobScalingConfig::escalation, DistanceEscalation::getMaxBonus), 0.0));
        this.escalationRarityChancePerPoint = Math.max(0.0, or(
                fold3(owner, store, jar, MobScalingConfig::escalation, DistanceEscalation::getRarityChancePerPoint), 0.0));

        // Difficulty stat curve (doubly-nested under Difficulty). The broken-jar fallbacks are the IDENTITY
        // curve (zero slopes, everything on the bar, rails at 1.0), so a broken-but-enabled jar leaves every
        // mob's stats exactly as the engine made them rather than flattening them to a tuning.
        MobScaleFold.DifficultyStatCurve none = MobScaleFold.DifficultyStatCurve.NONE;
        this.statCurve = buildCurve(
                or(fold3(owner, store, jar, MobScalingConfig::statCurve, StatCurve::getEffectiveHpPerPoint),
                        none.effectiveHpPerPoint()),
                or(fold3(owner, store, jar, MobScalingConfig::statCurve, StatCurve::getVisibleHpShare),
                        none.visibleHpShare()),
                or(fold3(owner, store, jar, MobScalingConfig::statCurve, StatCurve::getOutDamageScale),
                        none.outDamageScale()),
                or(fold3(owner, store, jar, MobScalingConfig::statCurve, StatCurve::getOutDamageShape),
                        none.outDamageShape()),
                or(fold3(owner, store, jar, MobScalingConfig::statCurve, StatCurve::getMaxEffectiveHpMult),
                        none.maxEffectiveHpMult()),
                or(fold3(owner, store, jar, MobScalingConfig::statCurve, StatCurve::getMaxOutDamageMult),
                        none.maxOutDamageMult()));
        // The safety clamps (doubly-nested under Difficulty). The fallbacks are the rails that never bind.
        MobScaleFold.Clamps noRails = MobScaleFold.Clamps.NONE;
        this.clamps = buildClamps(
                or(fold3(owner, store, jar, MobScalingConfig::clamps, Clamps::getMinHpMult), noRails.minHpMult()),
                or(fold3(owner, store, jar, MobScalingConfig::clamps, Clamps::getMaxInDamageMult), noRails.maxInDamageMult()),
                or(fold3(owner, store, jar, MobScalingConfig::clamps, Clamps::getMinOutDamageMult), noRails.minOutDamageMult()),
                or(fold3(owner, store, jar, MobScalingConfig::clamps, Clamps::getMinLootMult), noRails.minLootMult()),
                or(fold3(owner, store, jar, MobScalingConfig::clamps, Clamps::getMaxLootMult), noRails.maxLootMult()));

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

        // Drop the per-world resolved views: each overlay reads GLOBAL leaves off this fold, so a
        // preset swap / owner edit must re-resolve every cached world view (the rules themselves live
        // in WorldSettingsConfig and refold on their own triggers).
        this.worldViewCache.clear();
        this.nameOnlyViewCache.clear();
    }

    /** The doubly-nested escalation group ({@code Difficulty.DistanceEscalation}); {@code null} when absent. */
    @Nullable
    private static DistanceEscalation escalation(@Nonnull MobScalingSettingsAsset a) {
        Difficulty d = a.getDifficulty();
        return d == null ? null : d.getDistanceEscalation();
    }

    /** The triply-nested origin group ({@code Difficulty.DistanceEscalation.Origin}); {@code null} when absent. */
    @Nullable
    private static EscalationOrigin escalationOrigin(@Nonnull MobScalingSettingsAsset a) {
        DistanceEscalation e = escalation(a);
        return e == null ? null : e.getOrigin();
    }

    /** The doubly-nested stat-curve group ({@code Difficulty.StatCurve}); {@code null} when absent. */
    @Nullable
    private static StatCurve statCurve(@Nonnull MobScalingSettingsAsset a) {
        Difficulty d = a.getDifficulty();
        return d == null ? null : d.getStatCurve();
    }

    /** The doubly-nested clamps group ({@code Difficulty.Clamps}); {@code null} when absent. */
    @Nullable
    private static Clamps clamps(@Nonnull MobScalingSettingsAsset a) {
        Difficulty d = a.getDifficulty();
        return d == null ? null : d.getClamps();
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
     *
     * <p>It then logs the config locations at INFO on EVERY boot, not just the first: the paths are
     * relative to the SERVER's working directory (not to the jar), which is the single detail that makes
     * an owner conclude the config was never generated. The line prints ABSOLUTE paths so it is
     * copy-pasteable from the log.
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
                info("first run: wrote an empty override scaffold at " + absolute(owner)
                        + " - edit it to override defaults; the full schema is in _reference/" + REFERENCE_FILE);
            }
            logConfigLocations(owner, dir);
        } catch (Exception e) {
            warn("could not write the config scaffold (" + owner + "): " + e.getMessage());
        }
    }

    /**
     * One INFO line per boot naming the four places an owner edits this mod: the override file, the
     * regenerated schema reference beside it, the per-world rules folder and the zone/biome floors folder.
     */
    private static void logConfigLocations(@Nonnull Path owner, @Nullable Path dir) {
        String reference = dir == null ? "(none)" : absolute(dir.resolve("_reference").resolve(REFERENCE_FILE));
        String worlds = dir == null ? "(none)" : absolute(dir.resolve(WORLDS_DIR));
        String difficulty = dir == null ? "(none)" : absolute(dir.resolve(DIFFICULTY_DIR));
        info("config: " + absolute(owner) + " (schema reference: " + reference
                + ", per-world rules: " + worlds + ", zone/biome floors: " + difficulty + ")");
    }

    /** Absolute form of a path for a copy-pasteable log line; falls back to the raw path if unresolvable. */
    @Nonnull
    private static String absolute(@Nonnull Path path) {
        try {
            return path.toAbsolutePath().toString();
        } catch (Exception e) {
            return path.toString();
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
    @Override public boolean isOnlyRaiseDifficulty() { return onlyRaiseDifficulty; }
    @Override public boolean isPlayerScalingEnabled() { return playerScalingEnabled; }
    @Override public double getPlayerScalingStartRingBlocks() { return playerScalingStartRingBlocks; }
    @Override public double getRaritySpawnChance() { return raritySpawnChance; }
    @Nonnull public String getOpenWorldAggregationMode() { return openWorldAggregationMode; }
    public int getRegionSizeChunks() { return regionSizeChunks; }
    public double getGroupDeltaBandWidth() { return groupDeltaBandWidth; }
    /** GLOBAL view of the per-world kill-switch: the folded {@code Enabled} (systems gate at setup). */
    @Override public boolean isWorldScalingEnabled() { return enabled; }
    /** The GLOBAL world-baseline difficulty floor ({@code Difficulty.Floor}). */
    @Override public double getDifficultyFloor() { return difficultyFloor; }
    public double getDifficultyMinCap() { return difficultyMinCap; }
    public double getDifficultyMaxCap() { return difficultyMaxCap; }

    // GLOBAL pool view: no gates authored globally - every id rolls, neutral scale, no extra slots.
    @Override public boolean isRarityAllowed(@Nonnull String rarityId) { return true; }
    @Override public boolean isVariantAllowed(@Nonnull String variantId) { return true; }
    @Override public boolean isAffixAllowed(@Nonnull String affixId) { return true; }
    @Override public double getVariantChanceMultiplier() { return 1.0; }
    @Override public int getExtraAffixSlots() { return 0; }
    public boolean isDistanceEscalationEnabled() { return distanceEscalationEnabled; }
    @Override @Nullable public Double getEscalationOriginX() { return escalationOriginX; }
    @Override @Nullable public Double getEscalationOriginZ() { return escalationOriginZ; }
    public double getEscalationStartDistanceBlocks() { return escalationStartDistanceBlocks; }
    public double getEscalationBlocksPerPoint() { return escalationBlocksPerPoint; }
    public double getEscalationMaxBonus() { return escalationMaxBonus; }
    public double getEscalationRarityChancePerPoint() { return escalationRarityChancePerPoint; }
    // The folded StatCurve leaves, one getter per leaf (the per-world overlay and the admin page read them).
    public double getStatCurveEffectiveHpPerPoint() { return statCurve.effectiveHpPerPoint(); }
    public double getStatCurveVisibleHpShare() { return statCurve.visibleHpShare(); }
    public double getStatCurveOutDamageScale() { return statCurve.outDamageScale(); }
    public double getStatCurveOutDamageShape() { return statCurve.outDamageShape(); }
    public double getStatCurveMaxEffectiveHpMult() { return statCurve.maxEffectiveHpMult(); }
    public double getStatCurveMaxOutDamageMult() { return statCurve.maxOutDamageMult(); }
    // The folded Clamps leaves, one getter per leaf.
    public double getClampMinHpMult() { return clamps.minHpMult(); }
    public double getClampMaxInDamageMult() { return clamps.maxInDamageMult(); }
    public double getClampMinOutDamageMult() { return clamps.minOutDamageMult(); }
    public double getClampMinLootMult() { return clamps.minLootMult(); }
    public double getClampMaxLootMult() { return clamps.maxLootMult(); }

    /** The GLOBAL difficulty stat curve, built from the folded {@code Difficulty.StatCurve} leaves. */
    @Nonnull
    @Override
    public MobScaleFold.DifficultyStatCurve statCurveModel() {
        return statCurve;
    }

    /** The GLOBAL safety rails, built from the folded {@code Difficulty.Clamps} leaves. */
    @Nonnull
    @Override
    public MobScaleFold.Clamps clampsModel() {
        return clamps;
    }

    /**
     * Build a {@link MobScaleFold.DifficultyStatCurve} from six leaves with the one set of sanity clamps
     * every layer applies (a negative slope or scale would shrink a mob as difficulty rises; a share outside
     * [0, 1] or a ceiling under 1.0 is an impossible curve; a non-positive shape is not a curve at all and
     * reads as the straight line, 1.0). Shared by the global fold, the per-world overlay
     * ({@link ResolvedWorldSettings}) and the admin page's preview, so the three can never drift.
     */
    @Nonnull
    public static MobScaleFold.DifficultyStatCurve buildCurve(double effectiveHpPerPoint, double visibleHpShare,
            double outDamageScale, double outDamageShape, double maxEffectiveHpMult, double maxOutDamageMult) {
        return new MobScaleFold.DifficultyStatCurve(
                Math.max(0.0, effectiveHpPerPoint),
                Math.max(0.0, Math.min(1.0, visibleHpShare)),
                Math.max(0.0, outDamageScale),
                outDamageShape > 0.0 ? outDamageShape : 1.0,
                Math.max(1.0, maxEffectiveHpMult),
                Math.max(1.0, maxOutDamageMult));
    }


    /**
     * Build a {@link MobScaleFold.Clamps} from five leaves with the one set of sanity clamps every layer
     * applies (no negative floor, a positive incoming ceiling, an ordered loot band). Shared by the global
     * fold and the per-world overlay.
     */
    @Nonnull
    public static MobScaleFold.Clamps buildClamps(double minHpMult, double maxInDamageMult, double minOutDamageMult,
            double minLootMult, double maxLootMult) {
        double minLoot = Math.max(0.0, minLootMult);
        return new MobScaleFold.Clamps(
                Math.max(0.0, minHpMult),
                maxInDamageMult > 0.0 ? maxInDamageMult : MobScaleFold.Clamps.NONE.maxInDamageMult(),
                Math.max(0.0, minOutDamageMult),
                minLoot,
                Math.max(minLoot, maxLootMult));
    }

    // The GLOBAL HUD view; a world file overlays any of these per leaf through ResolvedWorldSettings.
    @Override public boolean isZoneHudEnabled() { return zoneHudEnabled; }
    @Override public boolean isZoneShowLocationName() { return zoneShowLocationName; }
    @Nonnull @Override public String getZoneHudPosition() { return zoneHudPosition; }
    @Override public int getZoneHudOffsetX() { return zoneHudOffsetX; }
    @Override public int getZoneHudOffsetY() { return zoneHudOffsetY; }
    @Nonnull @Override public String getZoneNameKeyPrefix() { return zoneNameKeyPrefix; }
    @Nonnull @Override public String getBiomeNameKeyPrefix() { return biomeNameKeyPrefix; }
    @Override public boolean isInspectorHudEnabled() { return inspectorHudEnabled; }
    @Nonnull @Override public String getInspectorHudPosition() { return inspectorHudPosition; }
    @Override public int getInspectorHudOffsetX() { return inspectorHudOffsetX; }
    @Override public int getInspectorHudOffsetY() { return inspectorHudOffsetY; }
    @Override public double getInspectorRangeBlocks() { return inspectorRangeBlocks; }
    @Override public boolean isInspectorPortraitEnabled() { return inspectorPortraitEnabled; }

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

    // ---------------------------------------------------------------------
    // Per-world settings overlay (Worlds/*.json via WorldSettingsConfig)
    // ---------------------------------------------------------------------

    /**
     * The effective spawn-time settings for {@code world}: the GLOBAL config itself when no
     * {@code Worlds/*.json} rule matches (zero-alloc common case), else a cached
     * {@link ResolvedWorldSettings} overlay where every exposed leaf is
     * {@code world-file-leaf ?? global} (the file itself is already {@code Parent}-merged by
     * {@link WorldSettingsConfig}). The cache is dropped on any refold ({@link #applyFold}) and on any
     * worlds refold ({@link #invalidateWorldViews}), so a reload / preset swap / owner-file edit takes
     * effect on the next spawn.
     *
     * <p>Prefer this form wherever the world is in hand: it scores BOTH axes, so a rule written on
     * the world's {@code GameplayConfig} key applies. The name-only form below cannot see it.
     */
    @Nonnull
    public SpawnScalingSettings spawnSettingsFor(@Nullable World world) {
        if (world == null) {
            return this;
        }
        String worldName = worldNameOf(world);
        if (worldName == null || WorldSettingsConfig.getInstance().rules().isEmpty()) {
            return this;
        }
        return worldViewCache.computeIfAbsent(worldName,
                name -> view(WorldSettingsConfig.getInstance().resolve(world)));
    }

    /**
     * The effective spawn-time settings for a world known only by NAME - the pure form, used where
     * no {@code World} handle exists. A rule written on a {@code GameplayConfig} key cannot match
     * here, so it is kept in its OWN cache: one world must never end up serving a view resolved from
     * fewer axes than the caller with the real world would have got.
     */
    @Nonnull
    public SpawnScalingSettings spawnSettingsFor(@Nullable String worldName) {
        if (worldName == null || worldName.isEmpty()
                || WorldSettingsConfig.getInstance().rules().isEmpty()) {
            return this;
        }
        return nameOnlyViewCache.computeIfAbsent(worldName,
                name -> view(WorldSettingsConfig.getInstance().resolve(name)));
    }

    @Nonnull
    private SpawnScalingSettings view(@Nullable WorldSettings ws) {
        return ws == null ? this : new ResolvedWorldSettings(this, ws);
    }

    @Nullable
    private static String worldNameOf(@Nonnull World world) {
        try {
            String name = world.getName();
            return name == null || name.isEmpty() ? null : name;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Drop every cached per-world view (called by {@link WorldSettingsConfig} on each worlds refold). */
    public void invalidateWorldViews() {
        this.worldViewCache.clear();
        this.nameOnlyViewCache.clear();
    }
}
