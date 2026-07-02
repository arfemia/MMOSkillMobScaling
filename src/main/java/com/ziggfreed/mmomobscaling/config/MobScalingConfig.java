package com.ziggfreed.mmomobscaling.config;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.logger.HytaleLogger;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset;

/**
 * The open-world mob-scaling configuration, driven ENTIRELY by an asset codec
 * ({@link MobScalingSettingsAsset}, Pattern A, PascalCase) - never Java-baked values, never a loose
 * JSON blob. The authoritative defaults ship as a codec asset
 * ({@code Server/MmoMobScaling/Settings/Default.json}); owners override any key in
 * {@code mods/MmoMobScaling/mob-scaling.json} (the SAME PascalCase codec shape, partial allowed).
 *
 * <p>Both layers are decoded SYNCHRONOUSLY via {@code MobScalingSettingsAsset.CODEC.decodeJson(...)}
 * at plugin {@code setup()} (the {@code WorldRulesConfig.decodeOwnerRule} pattern), so the zero-cost
 * registration gate can read {@link #isEnabled()} before a {@code LoadedAssetsEvent} async asset
 * store would populate. The effective value of each field is owner-over-default, computed here.
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

    // Effective (owner > store > jar) settings. The spawn-path reads (enabled, raritySpawnChance) are volatile
    // so a value written on the asset-load thread is visible on the world threads that read it per spawn.
    private volatile boolean enabled;
    private boolean compositionEnabled;
    @Nonnull private String presetMode = "";
    @Nonnull private String intensity = "";
    private volatile double raritySpawnChance;
    private boolean allowDifficultyIncreaseOnPartyJoin;
    private double lateArrivalBumpFactor;
    @Nonnull private String openWorldAggregationMode = "";
    private int regionSizeChunks;

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
        applyFold(jarDefaults, null, owner);
    }

    /**
     * Re-apply the settings from the loaded asset STORE (the engine-folded jar + pack layer, keyed
     * "Default") over the owner file. Called from the {@code LoadedAssetsEvent} listener AFTER
     * {@code setup()}, so a content pack's {@code Server/MmoMobScaling/Settings/Default.json} override
     * takes effect for the runtime-read fields. Uses the SAME codec + fold as {@link #load()}.
     */
    public void applyStoreLayer(@Nonnull MobScalingSettingsAsset storeDefaults) {
        MobScalingSettingsAsset owner = decode(readOwnerFile(), ownerLabel());
        applyFold(jarDefaults, storeDefaults, owner);
    }

    /**
     * Fold {@code owner > store > jar} per field (a nullable key falls to the next layer, then the neutral
     * fail-safe). Folding the JAR layer UNDERNEATH the store means a PARTIAL pack override (wholesale replace)
     * that omits a key inherits the jar value, not the fail-safe - so a pack tuning only {@code RaritySpawnChance}
     * can never accidentally fold {@code enabled} to {@code false} and silently kill the mod at runtime.
     */
    private void applyFold(@Nullable MobScalingSettingsAsset jar, @Nullable MobScalingSettingsAsset store,
            @Nullable MobScalingSettingsAsset owner) {
        this.enabled = pick(get(owner, MobScalingSettingsAsset::getEnabled),
                get(store, MobScalingSettingsAsset::getEnabled), get(jar, MobScalingSettingsAsset::getEnabled), false);
        this.compositionEnabled = pick(get(owner, MobScalingSettingsAsset::getCompositionEnabled),
                get(store, MobScalingSettingsAsset::getCompositionEnabled),
                get(jar, MobScalingSettingsAsset::getCompositionEnabled), false);
        this.presetMode = pick(get(owner, MobScalingSettingsAsset::getPresetMode),
                get(store, MobScalingSettingsAsset::getPresetMode), get(jar, MobScalingSettingsAsset::getPresetMode), "");
        this.intensity = pick(get(owner, MobScalingSettingsAsset::getIntensity),
                get(store, MobScalingSettingsAsset::getIntensity), get(jar, MobScalingSettingsAsset::getIntensity), "");
        double chance = pick(get(owner, MobScalingSettingsAsset::getRaritySpawnChance),
                get(store, MobScalingSettingsAsset::getRaritySpawnChance),
                get(jar, MobScalingSettingsAsset::getRaritySpawnChance), 0.0);
        this.raritySpawnChance = Math.max(0.0, Math.min(1.0, chance)); // clamp: an unclamped chance is a footgun
        this.allowDifficultyIncreaseOnPartyJoin = pick(
                get(owner, MobScalingSettingsAsset::getAllowDifficultyIncreaseOnPartyJoin),
                get(store, MobScalingSettingsAsset::getAllowDifficultyIncreaseOnPartyJoin),
                get(jar, MobScalingSettingsAsset::getAllowDifficultyIncreaseOnPartyJoin), false);
        this.lateArrivalBumpFactor = pick(get(owner, MobScalingSettingsAsset::getLateArrivalBumpFactor),
                get(store, MobScalingSettingsAsset::getLateArrivalBumpFactor),
                get(jar, MobScalingSettingsAsset::getLateArrivalBumpFactor), 0.0);
        this.openWorldAggregationMode = pick(get(owner, MobScalingSettingsAsset::getOpenWorldAggregationMode),
                get(store, MobScalingSettingsAsset::getOpenWorldAggregationMode),
                get(jar, MobScalingSettingsAsset::getOpenWorldAggregationMode), "");
        this.regionSizeChunks = pick(get(owner, MobScalingSettingsAsset::getRegionSizeChunks),
                get(store, MobScalingSettingsAsset::getRegionSizeChunks),
                get(jar, MobScalingSettingsAsset::getRegionSizeChunks), 0);
    }

    /** Read a field off a nullable asset via its getter; {@code null} when the asset is null. */
    @Nullable
    private static <T> T get(@Nullable MobScalingSettingsAsset a,
            @Nonnull java.util.function.Function<MobScalingSettingsAsset, T> getter) {
        return a == null ? null : getter.apply(a);
    }

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

    // Fold order owner > store > jar > neutral fallback (first non-null wins).
    private static boolean pick(@Nullable Boolean owner, @Nullable Boolean store, @Nullable Boolean jar, boolean fb) {
        if (owner != null) return owner;
        if (store != null) return store;
        if (jar != null) return jar;
        return fb;
    }

    private static double pick(@Nullable Double owner, @Nullable Double store, @Nullable Double jar, double fb) {
        if (owner != null) return owner;
        if (store != null) return store;
        if (jar != null) return jar;
        return fb;
    }

    private static int pick(@Nullable Integer owner, @Nullable Integer store, @Nullable Integer jar, int fb) {
        if (owner != null) return owner;
        if (store != null) return store;
        if (jar != null) return jar;
        return fb;
    }

    @Nonnull
    private static String pick(@Nullable String owner, @Nullable String store, @Nullable String jar,
            @Nonnull String fb) {
        if (owner != null) return owner;
        if (store != null) return store;
        if (jar != null) return jar;
        return fb;
    }

    // ---------------------------------------------------------------------
    // Getters
    // ---------------------------------------------------------------------

    public boolean isEnabled() { return enabled; }
    public boolean isCompositionEnabled() { return compositionEnabled; }
    @Nonnull public String getPresetMode() { return presetMode; }
    @Nonnull public String getIntensity() { return intensity; }
    public double getRaritySpawnChance() { return raritySpawnChance; }
    public boolean isAllowDifficultyIncreaseOnPartyJoin() { return allowDifficultyIncreaseOnPartyJoin; }
    public double getLateArrivalBumpFactor() { return lateArrivalBumpFactor; }
    @Nonnull public String getOpenWorldAggregationMode() { return openWorldAggregationMode; }
    public int getRegionSizeChunks() { return regionSizeChunks; }
}
