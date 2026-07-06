package com.ziggfreed.mmomobscaling.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.mmomobscaling.MobScalingGate;
import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset;

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
        assertEquals("AVERAGE", cfg.getOpenWorldAggregationMode(), "OpenWorld.AggregationMode default");
        assertEquals(3, cfg.getRegionSizeChunks(), "OpenWorld.RegionSizeChunks default");
        assertEquals(25.0, cfg.getGroupDeltaBandWidth(), 1e-9, "OpenWorld.GroupDeltaBandWidth default");
        assertEquals(1.0, cfg.getDifficultyMinCap(), 1e-9, "Difficulty.MinCap default");
        assertEquals(200.0, cfg.getDifficultyMaxCap(), 1e-9, "Difficulty.MaxCap default");
        assertTrue(cfg.isDistanceEscalationEnabled(), "Difficulty.DistanceEscalation.Enabled default");
        assertEquals(5000.0, cfg.getEscalationStartDistanceBlocks(), 1e-9, "escalation start default");
        assertEquals(500.0, cfg.getEscalationBlocksPerPoint(), 1e-9, "escalation slope default");
        assertEquals(199.0, cfg.getEscalationMaxBonus(), 1e-9, "escalation cap default");
        assertEquals(0.01, cfg.getEscalationRarityChancePerPoint(), 1e-9, "escalation chance-bonus default");
        // Difficulty.StatCurve: the shipped steep per-difficulty stat curve.
        assertEquals(0.08, cfg.getStatCurveHpPerPoint(), 1e-9, "StatCurve.HpPerPoint default");
        assertEquals(0.06, cfg.getStatCurveOutDamagePerPoint(), 1e-9, "StatCurve.OutDamagePerPoint default");
        assertEquals(0.002, cfg.getStatCurveInDamageReductionPerPoint(), 1e-9,
                "StatCurve.InDamageReductionPerPoint default");
        assertEquals(20.0, cfg.getStatCurveMaxHpMult(), 1e-9, "StatCurve.MaxHpMult default");
        assertEquals(12.0, cfg.getStatCurveMaxOutDamageMult(), 1e-9, "StatCurve.MaxOutDamageMult default");
        assertEquals(0.5, cfg.getStatCurveMinInDamageMult(), 1e-9, "StatCurve.MinInDamageMult default");
        assertTrue(cfg.isZoneHudEnabled(), "ZoneHudEnabled default");
        assertEquals("TOP_LEFT", cfg.getZoneHudPosition(), "ZoneHudPosition default");
        assertEquals(16, cfg.getZoneHudOffsetX(), "ZoneHudOffsetX default");
        assertEquals(90, cfg.getZoneHudOffsetY(), "ZoneHudOffsetY default");
        assertTrue(cfg.isInspectorHudEnabled(), "InspectorHudEnabled default");
        assertEquals("TOP_LEFT", cfg.getInspectorHudPosition(), "InspectorHudPosition default (tucked under the zone HUD)");
        assertEquals(16, cfg.getInspectorHudOffsetX(), "InspectorHudOffsetX default");
        assertEquals(216, cfg.getInspectorHudOffsetY(), "InspectorHudOffsetY default");
        assertEquals(12.0, cfg.getInspectorRangeBlocks(), 1e-9, "InspectorRangeBlocks default");
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

    @Test
    void partiallyFilledNestedGroupFoldsPerLeaf(@TempDir Path tmp) throws Exception {
        Path configFile = tmp.resolve("mob-scaling.json");
        // Nested groups may be PARTIALLY filled: only the set LEAF overrides; sibling leaves in the
        // same group (and the doubly-nested escalation) keep their codec defaults.
        Files.writeString(configFile, """
                {
                  "OpenWorld": { "RegionSizeChunks": 5 },
                  "Difficulty": {
                    "MaxCap": 150.0,
                    "DistanceEscalation": { "MaxBonus": 40.0 },
                    "StatCurve": { "HpPerPoint": 0.2 }
                  },
                  "ZoneHud": { "Enabled": false }
                }
                """);

        MobScalingConfig cfg = MobScalingConfig.getInstance();
        cfg.setConfigPath(configFile);
        cfg.load();

        assertEquals(5, cfg.getRegionSizeChunks(), "owner nested leaf applied");
        assertEquals("AVERAGE", cfg.getOpenWorldAggregationMode(), "sibling leaf in the same group stays default");
        assertEquals(25.0, cfg.getGroupDeltaBandWidth(), 1e-9, "sibling leaf in the same group stays default");
        assertEquals(150.0, cfg.getDifficultyMaxCap(), 1e-9, "owner nested cap applied");
        assertEquals(1.0, cfg.getDifficultyMinCap(), 1e-9, "sibling cap stays default");
        assertEquals(40.0, cfg.getEscalationMaxBonus(), 1e-9, "doubly-nested owner leaf applied");
        assertEquals(5000.0, cfg.getEscalationStartDistanceBlocks(), 1e-9,
                "doubly-nested sibling leaf stays default");
        // Doubly-nested StatCurve: the owner sets only HpPerPoint; sibling leaves keep the Default.
        assertEquals(0.2, cfg.getStatCurveHpPerPoint(), 1e-9, "doubly-nested StatCurve owner leaf applied");
        assertEquals(20.0, cfg.getStatCurveMaxHpMult(), 1e-9, "StatCurve sibling leaf stays default");
        assertFalse(cfg.isZoneHudEnabled(), "owner ZoneHud.Enabled applied");
        assertEquals("TOP_LEFT", cfg.getZoneHudPosition(), "ZoneHud.Position stays default");
        assertTrue(cfg.isInspectorHudEnabled(), "untouched group stays default");
    }

    @Test
    void applyStoreLayerFoldsTheDecodedAssetOverOwner() throws Exception {
        // The async store layer in production is the engine-folded jar Default.json. Decode that
        // same bundled codec asset and confirm applyStoreLayer yields the codec defaults (no owner).
        MobScalingSettingsAsset store;
        try (InputStream in = MobScalingConfigTest.class.getResourceAsStream(
                "/Server/MmoMobScaling/Settings/Default.json")) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            store = MobScalingSettingsAsset.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
        }

        MobScalingConfig cfg = MobScalingConfig.getInstance();
        cfg.setConfigPath(null); // no owner overlay
        cfg.applyStoreLayer(store);

        assertTrue(cfg.isEnabled(), "store-layer Enabled");
        assertEquals("SIMPLE", cfg.getPresetMode(), "store-layer PresetMode");
        assertEquals(0.12, cfg.getRaritySpawnChance(), 1e-9, "store-layer RaritySpawnChance");
        assertEquals(3, cfg.getRegionSizeChunks(), "store-layer RegionSizeChunks");
    }
}
