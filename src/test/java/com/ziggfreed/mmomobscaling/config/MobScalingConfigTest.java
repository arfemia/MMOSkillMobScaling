package com.ziggfreed.mmomobscaling.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ziggfreed.mmomobscaling.MobScalingGate;

/**
 * Unit tests for {@link MobScalingConfig} and the {@link MobScalingGate} registration gate.
 * These run in a log-manager-less unit JVM; the MMO's SafeLog (reached through the inherited
 * {@link com.ziggfreed.mmoskilltree.config.AbstractOverrideConfig} scaffolding) is
 * try-guarded and degrades to a no-op there, so full load()/save() file IO is safe.
 *
 * <p>The gate is exercised through {@link MobScalingGate} rather than
 * {@code MobScalingPlugin.shouldRegisterSystems}: loading the {@code JavaPlugin}-extending
 * plugin class in a unit JVM fails (its {@code PluginBase} -> {@code MetricsRegistry}
 * static-init chain throws without a running server). {@code MobScalingPlugin} delegates to
 * this same predicate, so testing it here fully covers the gate decision.
 */
class MobScalingConfigTest {

    /** Fresh singleton state each test: reload the defaults before asserting. */
    private static MobScalingConfig freshDefaults() {
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        cfg.setConfigPath(null);
        cfg.load(); // clearAll() + loadDefaults(); no file at a null path, so defaults only
        return cfg;
    }

    @Test
    void defaultsAreCorrect() {
        MobScalingConfig cfg = freshDefaults();

        assertTrue(cfg.isEnabled(), "enabled default");
        assertEquals("SIMPLE", cfg.getPresetMode(), "presetMode default");
        assertEquals("medium", cfg.getIntensity(), "intensity default");
        assertEquals(0.12, cfg.getRaritySpawnChance(), 1e-9, "raritySpawnChance default");
        assertFalse(cfg.isCompositionEnabled(), "compositionEnabled default");
        assertTrue(cfg.isAllowDifficultyIncreaseOnPartyJoin(), "allowDifficultyIncreaseOnPartyJoin default");
        assertEquals(5.0, cfg.getLateArrivalBumpFactor(), 1e-9, "lateArrivalBumpFactor default");
        assertEquals(3, cfg.getRegionSizeChunks(), "regionSizeChunks default");
        assertEquals("AVERAGE", cfg.getOpenWorldAggregationMode(), "openWorldAggregationMode default");

        assertEquals(Integer.valueOf(70), cfg.getRarityWeights().get("rare"), "rarityWeights rare");
        assertEquals(Integer.valueOf(25), cfg.getRarityWeights().get("epic"), "rarityWeights epic");
        assertEquals(Integer.valueOf(5), cfg.getRarityWeights().get("legendary"), "rarityWeights legendary");
        assertEquals(Double.valueOf(25.0), cfg.getZoneOverrides().get("Zone2"), "zoneOverrides Zone2");
        assertEquals(Double.valueOf(55.0), cfg.getZoneOverrides().get("Zone4"), "zoneOverrides Zone4");
    }

    @Test
    void gateReflectsEnabledFlag() {
        // enabled default -> gate true
        MobScalingConfig cfg = freshDefaults();
        assertTrue(MobScalingGate.shouldRegisterSystems(cfg), "enabled config should register");

        // null config -> gate false (defensive)
        assertFalse(MobScalingGate.shouldRegisterSystems(null), "null config should not register");
    }

    @Test
    void gateFalseWhenDisabled(@TempDir Path tmp) throws Exception {
        // Persist a disabled override, then reload from disk and confirm the gate is false.
        MobScalingConfig cfg = freshDefaults();
        assertTrue(cfg.isEnabled(), "precondition: default enabled");

        Path configFile = tmp.resolve("mob-scaling.json");
        writeDisabledOverride(configFile);

        cfg.setConfigPath(configFile);
        cfg.load();

        assertFalse(cfg.isEnabled(), "disabled override should read back as disabled");
        assertFalse(MobScalingGate.shouldRegisterSystems(cfg), "disabled config should not register");
    }

    @Test
    void overrideRoundTripPreservesValue(@TempDir Path tmp) throws Exception {
        Path configFile = tmp.resolve("mob-scaling.json");

        // Start from defaults, write a disabled override manually, load it back.
        MobScalingConfig cfg = freshDefaults();
        writeDisabledOverride(configFile);

        cfg.setConfigPath(configFile);
        cfg.load();
        assertFalse(cfg.isEnabled(), "override read back after load");

        // Now re-save (writeConfigData persists the single non-null override) and reload.
        cfg.save();
        assertTrue(Files.exists(configFile), "config file written by save()");

        // A fresh load from the same file must still be disabled -> the round-trip preserved it.
        cfg.load();
        assertFalse(cfg.isEnabled(), "override survived a save->load round-trip");
        // getOverrideCount() is protected in MobScalingConfig; this test lives in the same
        // package (com.ziggfreed.mmomobscaling.config), so it is directly accessible.
        assertEquals(1, cfg.getOverrideCount(), "exactly one override persisted");
    }

    /** Writes a minimal {schemaVersion, overrides:{enabled:false}} config JSON. */
    private static void writeDisabledOverride(Path configFile) throws Exception {
        Files.createDirectories(configFile.getParent());
        String json = "{\n"
                + "  \"schemaVersion\": " + MobScalingConfig.SCHEMA_VERSION + ",\n"
                + "  \"overrides\": { \"enabled\": false }\n"
                + "}\n";
        Files.writeString(configFile, json);
    }
}
