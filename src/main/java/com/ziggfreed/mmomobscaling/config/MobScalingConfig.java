package com.ziggfreed.mmomobscaling.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.mmoskilltree.config.AbstractOverrideConfig;

/**
 * Override-based configuration for the open-world mob-scaling system.
 *
 * <p>Config file: mods/MmoMobScaling/mob-scaling.json
 *
 * <p>Only stores user customizations (overrides); code defaults are supplied by
 * {@link #loadDefaults()} and merged at load time. This mirrors the MMO Skill Tree's
 * {@code EliteMobsConfig} pattern (nullable-wrapper {@code OverrideData} + the
 * {schemaVersion, overrides} ConfigData JSON shape) and extends the same
 * {@link AbstractOverrideConfig} base (provided by the MMOSkillTree jar at runtime).
 *
 * <p>The v1.0.0 field set is the SIMPLE preset's starter numbers; the build systems
 * that consume these land in a later phase.
 */
public class MobScalingConfig extends AbstractOverrideConfig {

    public static final int SCHEMA_VERSION = 1;

    private static MobScalingConfig instance;

    // ==================== Effective state (defaults + overrides) ====================

    private boolean enabled;
    private String presetMode;
    private String intensity;
    private double raritySpawnChance;
    private Map<String, Integer> rarityWeights = new LinkedHashMap<>();
    private Map<String, Double> zoneOverrides = new LinkedHashMap<>();
    private boolean allowDifficultyIncreaseOnPartyJoin;
    private double lateArrivalBumpFactor;
    private String openWorldAggregationMode;
    private boolean compositionEnabled;
    private int regionSizeChunks;

    // ==================== User overrides (what gets persisted) ====================

    private final OverrideData userOverrides = new OverrideData();

    // ==================== Singleton ====================

    private MobScalingConfig() {}

    @Nonnull
    public static MobScalingConfig getInstance() {
        if (instance == null) {
            instance = new MobScalingConfig();
        }
        return instance;
    }

    // ==================== AbstractOverrideConfig ====================

    @Override
    protected String configName() { return "mob-scaling"; }

    @Override
    protected int schemaVersion() { return SCHEMA_VERSION; }

    @Override
    protected void loadDefaults() {
        this.enabled = true;
        this.presetMode = "SIMPLE";
        this.intensity = "medium";
        this.raritySpawnChance = 0.12;

        Map<String, Integer> weights = new LinkedHashMap<>();
        weights.put("rare", 70);
        weights.put("epic", 25);
        weights.put("legendary", 5);
        this.rarityWeights = weights;

        Map<String, Double> zones = new LinkedHashMap<>();
        zones.put("Zone2", 25.0);
        zones.put("Zone4", 55.0);
        this.zoneOverrides = zones;

        this.allowDifficultyIncreaseOnPartyJoin = true;
        this.lateArrivalBumpFactor = 5.0;
        this.openWorldAggregationMode = "AVERAGE";
        this.compositionEnabled = false;
        this.regionSizeChunks = 3;
    }

    @Override
    protected void clearAll() {
        this.rarityWeights = new LinkedHashMap<>();
        this.zoneOverrides = new LinkedHashMap<>();
        this.userOverrides.clear();
    }

    @Override
    protected void readOverrides(@Nonnull Reader reader) throws Exception {
        ConfigData data = GSON.fromJson(reader, ConfigData.class);
        if (data == null || data.overrides == null) {
            return;
        }
        OverrideData ov = data.overrides;

        if (ov.enabled != null) {
            this.enabled = ov.enabled;
            userOverrides.enabled = ov.enabled;
        }
        if (ov.presetMode != null) {
            this.presetMode = ov.presetMode;
            userOverrides.presetMode = ov.presetMode;
        }
        if (ov.intensity != null) {
            this.intensity = ov.intensity;
            userOverrides.intensity = ov.intensity;
        }
        if (ov.raritySpawnChance != null) {
            this.raritySpawnChance = ov.raritySpawnChance;
            userOverrides.raritySpawnChance = ov.raritySpawnChance;
        }
        if (ov.rarityWeights != null) {
            this.rarityWeights = new LinkedHashMap<>(ov.rarityWeights);
            userOverrides.rarityWeights = ov.rarityWeights;
        }
        if (ov.zoneOverrides != null) {
            this.zoneOverrides = new LinkedHashMap<>(ov.zoneOverrides);
            userOverrides.zoneOverrides = ov.zoneOverrides;
        }
        if (ov.allowDifficultyIncreaseOnPartyJoin != null) {
            this.allowDifficultyIncreaseOnPartyJoin = ov.allowDifficultyIncreaseOnPartyJoin;
            userOverrides.allowDifficultyIncreaseOnPartyJoin = ov.allowDifficultyIncreaseOnPartyJoin;
        }
        if (ov.lateArrivalBumpFactor != null) {
            this.lateArrivalBumpFactor = ov.lateArrivalBumpFactor;
            userOverrides.lateArrivalBumpFactor = ov.lateArrivalBumpFactor;
        }
        if (ov.openWorldAggregationMode != null) {
            this.openWorldAggregationMode = ov.openWorldAggregationMode;
            userOverrides.openWorldAggregationMode = ov.openWorldAggregationMode;
        }
        if (ov.compositionEnabled != null) {
            this.compositionEnabled = ov.compositionEnabled;
            userOverrides.compositionEnabled = ov.compositionEnabled;
        }
        if (ov.regionSizeChunks != null) {
            this.regionSizeChunks = ov.regionSizeChunks;
            userOverrides.regionSizeChunks = ov.regionSizeChunks;
        }
    }

    @Override
    protected int getOverrideCount() {
        return userOverrides.countNonNull();
    }

    @Override
    protected void writeConfigData(@Nonnull Writer writer) throws IOException {
        ConfigData data = new ConfigData();
        data.schemaVersion = SCHEMA_VERSION;
        data.overrides = userOverrides.isEmpty() ? null : userOverrides;
        GSON.toJson(data, writer);
    }

    @Override
    protected boolean supportsReferenceFile() { return false; }

    // ==================== Getters ====================

    public boolean isEnabled() { return enabled; }
    public boolean isCompositionEnabled() { return compositionEnabled; }

    @Nonnull
    public String getPresetMode() { return presetMode; }

    @Nonnull
    public String getIntensity() { return intensity; }

    public double getRaritySpawnChance() { return raritySpawnChance; }

    @Nonnull
    public Map<String, Integer> getRarityWeights() { return rarityWeights; }

    @Nonnull
    public Map<String, Double> getZoneOverrides() { return zoneOverrides; }

    public boolean isAllowDifficultyIncreaseOnPartyJoin() { return allowDifficultyIncreaseOnPartyJoin; }

    public double getLateArrivalBumpFactor() { return lateArrivalBumpFactor; }

    @Nonnull
    public String getOpenWorldAggregationMode() { return openWorldAggregationMode; }

    public int getRegionSizeChunks() { return regionSizeChunks; }

    // ==================== Internal Data Classes ====================

    /**
     * Nullable override fields. A null field means "use the default"; only non-null
     * fields are persisted and re-applied on load.
     */
    private static class OverrideData {
        Boolean enabled;
        String presetMode;
        String intensity;
        Double raritySpawnChance;
        Map<String, Integer> rarityWeights;
        Map<String, Double> zoneOverrides;
        Boolean allowDifficultyIncreaseOnPartyJoin;
        Double lateArrivalBumpFactor;
        String openWorldAggregationMode;
        Boolean compositionEnabled;
        Integer regionSizeChunks;

        void clear() {
            enabled = null;
            presetMode = null;
            intensity = null;
            raritySpawnChance = null;
            rarityWeights = null;
            zoneOverrides = null;
            allowDifficultyIncreaseOnPartyJoin = null;
            lateArrivalBumpFactor = null;
            openWorldAggregationMode = null;
            compositionEnabled = null;
            regionSizeChunks = null;
        }

        boolean isEmpty() {
            return countNonNull() == 0;
        }

        int countNonNull() {
            int count = 0;
            if (enabled != null) count++;
            if (presetMode != null) count++;
            if (intensity != null) count++;
            if (raritySpawnChance != null) count++;
            if (rarityWeights != null) count++;
            if (zoneOverrides != null) count++;
            if (allowDifficultyIncreaseOnPartyJoin != null) count++;
            if (lateArrivalBumpFactor != null) count++;
            if (openWorldAggregationMode != null) count++;
            if (compositionEnabled != null) count++;
            if (regionSizeChunks != null) count++;
            return count;
        }
    }

    private static class ConfigData {
        int schemaVersion;
        @Nullable OverrideData overrides;
    }
}
