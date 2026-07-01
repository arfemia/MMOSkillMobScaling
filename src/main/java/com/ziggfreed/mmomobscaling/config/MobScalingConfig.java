package com.ziggfreed.mmomobscaling.config;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
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

    private static MobScalingConfig instance;

    @Nullable private Path configPath;

    // Effective (owner-over-default) settings. Initialized to a neutral fail-safe; overwritten by load().
    private boolean enabled;
    private boolean compositionEnabled;
    @Nonnull private String presetMode = "";
    @Nonnull private String intensity = "";
    private double raritySpawnChance;
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
        MobScalingSettingsAsset defaults = decode(readResource(DEFAULTS_RESOURCE));
        MobScalingSettingsAsset owner = decode(readOwnerFile());
        applyFold(defaults, owner);
    }

    /**
     * Re-apply the settings from the loaded asset STORE (the engine-folded jar + pack layer, keyed
     * "Default") over the owner file. Called from the {@code LoadedAssetsEvent} listener AFTER
     * {@code setup()}, so a content pack's {@code Server/MmoMobScaling/Settings/Default.json} override
     * takes effect for the runtime-read fields. Uses the SAME codec + fold as {@link #load()}.
     */
    public void applyStoreLayer(@Nonnull MobScalingSettingsAsset storeDefaults) {
        MobScalingSettingsAsset owner = decode(readOwnerFile());
        applyFold(storeDefaults, owner);
    }

    /** Owner value wins over default; a neutral fallback only if BOTH are absent (broken bundled jar). */
    private void applyFold(@Nullable MobScalingSettingsAsset d, @Nullable MobScalingSettingsAsset o) {
        this.enabled = pick(o == null ? null : o.getEnabled(), d == null ? null : d.getEnabled(), false);
        this.compositionEnabled = pick(o == null ? null : o.getCompositionEnabled(),
                d == null ? null : d.getCompositionEnabled(), false);
        this.presetMode = pick(o == null ? null : o.getPresetMode(), d == null ? null : d.getPresetMode(), "");
        this.intensity = pick(o == null ? null : o.getIntensity(), d == null ? null : d.getIntensity(), "");
        this.raritySpawnChance = pick(o == null ? null : o.getRaritySpawnChance(),
                d == null ? null : d.getRaritySpawnChance(), 0.0);
        this.allowDifficultyIncreaseOnPartyJoin = pick(o == null ? null : o.getAllowDifficultyIncreaseOnPartyJoin(),
                d == null ? null : d.getAllowDifficultyIncreaseOnPartyJoin(), false);
        this.lateArrivalBumpFactor = pick(o == null ? null : o.getLateArrivalBumpFactor(),
                d == null ? null : d.getLateArrivalBumpFactor(), 0.0);
        this.openWorldAggregationMode = pick(o == null ? null : o.getOpenWorldAggregationMode(),
                d == null ? null : d.getOpenWorldAggregationMode(), "");
        this.regionSizeChunks = pick(o == null ? null : o.getRegionSizeChunks(),
                d == null ? null : d.getRegionSizeChunks(), 0);
    }

    // ---------------------------------------------------------------------
    // Codec decode (synchronous)
    // ---------------------------------------------------------------------

    /** Decode a settings body via the codec; {@code null} for a null/blank body or any decode error. */
    @Nullable
    private static MobScalingSettingsAsset decode(@Nullable String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return MobScalingSettingsAsset.CODEC.decodeJson(RawJsonReader.fromJsonString(body), new ExtraInfo());
        } catch (Exception e) {
            return null;
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

    private static boolean pick(@Nullable Boolean owner, @Nullable Boolean def, boolean fallback) {
        if (owner != null) return owner;
        if (def != null) return def;
        return fallback;
    }

    private static double pick(@Nullable Double owner, @Nullable Double def, double fallback) {
        if (owner != null) return owner;
        if (def != null) return def;
        return fallback;
    }

    private static int pick(@Nullable Integer owner, @Nullable Integer def, int fallback) {
        if (owner != null) return owner;
        if (def != null) return def;
        return fallback;
    }

    @Nonnull
    private static String pick(@Nullable String owner, @Nullable String def, @Nonnull String fallback) {
        if (owner != null) return owner;
        if (def != null) return def;
        return fallback;
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
