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
 *
 * <p>The config is driven entirely by the {@link com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset}
 * codec: defaults decode from the jar-bundled {@code Server/MmoMobScaling/Settings/Default.json}
 * (on the test classpath via main resources), and a partial owner file overlays it. There are no
 * Java-baked config values to assert against - these tests verify the CODEC-decoded defaults + the
 * owner-over-default fold + the gate.
 *
 * <p>The gate is exercised through {@link MobScalingGate} rather than
 * {@code MobScalingPlugin.shouldRegisterSystems}: loading the {@code JavaPlugin}-extending plugin
 * class in a unit JVM fails (its {@code PluginBase} -> {@code MetricsRegistry} static-init chain
 * throws without a running server). {@code MobScalingPlugin} delegates to this same predicate.
 */
class MobScalingConfigTest {

    /** Fresh singleton loaded from the jar defaults only (no owner file). */
    private static MobScalingConfig freshDefaults() {
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        cfg.setConfigPath(null);
        cfg.load();
        return cfg;
    }

    @Test
    void defaultsDecodeFromTheCodecAsset() {
        MobScalingConfig cfg = freshDefaults();

        assertTrue(cfg.isEnabled(), "Enabled default");
        assertFalse(cfg.isCompositionEnabled(), "CompositionEnabled default");
        assertEquals("SIMPLE", cfg.getPresetMode(), "PresetMode default");
        assertEquals("medium", cfg.getIntensity(), "Intensity default");
        assertEquals(0.12, cfg.getRaritySpawnChance(), 1e-9, "RaritySpawnChance default");
        assertTrue(cfg.isAllowDifficultyIncreaseOnPartyJoin(), "AllowDifficultyIncreaseOnPartyJoin default");
        assertEquals(5.0, cfg.getLateArrivalBumpFactor(), 1e-9, "LateArrivalBumpFactor default");
        assertEquals("AVERAGE", cfg.getOpenWorldAggregationMode(), "OpenWorldAggregationMode default");
        assertEquals(3, cfg.getRegionSizeChunks(), "RegionSizeChunks default");
    }

    @Test
    void gateReflectsEnabledFlag() {
        MobScalingConfig cfg = freshDefaults();
        assertTrue(MobScalingGate.shouldRegisterSystems(cfg), "enabled config should register");
        assertFalse(MobScalingGate.shouldRegisterSystems(null), "null config should not register");
    }

    @Test
    void ownerFileOverlaysOnlyItsKeys(@TempDir Path tmp) throws Exception {
        Path configFile = tmp.resolve("mob-scaling.json");
        // A PARTIAL owner file (PascalCase codec shape): flips Enabled, leaves everything else default.
        Files.writeString(configFile, "{\n  \"Enabled\": false,\n  \"Intensity\": \"brutal\"\n}\n");

        MobScalingConfig cfg = MobScalingConfig.getInstance();
        cfg.setConfigPath(configFile);
        cfg.load();

        assertFalse(cfg.isEnabled(), "owner Enabled override applied");
        assertEquals("brutal", cfg.getIntensity(), "owner Intensity override applied");
        // Unset owner keys fall back to the codec default, NOT a neutral zero.
        assertEquals("SIMPLE", cfg.getPresetMode(), "unset key falls back to codec default");
        assertEquals(0.12, cfg.getRaritySpawnChance(), 1e-9, "unset key falls back to codec default");
        assertEquals(3, cfg.getRegionSizeChunks(), "unset key falls back to codec default");
        assertFalse(MobScalingGate.shouldRegisterSystems(cfg), "disabled owner config should not register");
    }
}
