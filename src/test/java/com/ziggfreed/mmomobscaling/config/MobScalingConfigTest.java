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
import com.ziggfreed.mmomobscaling.scaling.MobScaleFold;

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
        assertEquals(1.0, cfg.getIntensity(), 1e-9, "Intensity default (numeric, 1.0.1)");
        assertTrue(cfg.isPlayerScalingEnabled(), "PlayerScalingEnabled default (global, 1.0.1)");
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
        assertEquals(0.04, cfg.getStatCurveOutDamagePerPoint(), 1e-9, "StatCurve.OutDamagePerPoint default");
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
        Files.writeString(configFile, "{\n  \"Enabled\": false,\n  \"Intensity\": 2.0\n}\n");

        MobScalingConfig cfg = MobScalingConfig.getInstance();
        cfg.setConfigPath(configFile);
        cfg.load();

        assertFalse(cfg.isEnabled(), "owner Enabled override applied");
        assertEquals(2.0, cfg.getIntensity(), 1e-9, "owner Intensity override applied");
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

    // ---------------------------------------------------------------------
    // 1.0.1: numeric Intensity + per-world overlay
    // ---------------------------------------------------------------------

    @Test
    void intensityScalesTheStatCurveSlopes() {
        MobScalingConfig cfg = freshDefaults(); // Intensity default 1.0
        MobScaleFold.DifficultyStatCurve curve = cfg.statCurveModel();
        assertEquals(0.08, curve.hpPerPoint(), 1e-9, "intensity 1.0 leaves the slope unchanged");
        assertEquals(0.04, curve.outPerPoint(), 1e-9, "intensity 1.0 leaves the out slope unchanged");
        // The caps are NOT scaled by intensity.
        assertEquals(20.0, curve.maxHpMult(), 1e-9, "intensity does not scale caps");
    }

    @Test
    void ownerIntensityMultipliesTheStatCurve(@TempDir Path tmp) throws Exception {
        Path configFile = tmp.resolve("mob-scaling.json");
        Files.writeString(configFile, "{\n  \"Intensity\": 2.0\n}\n");
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        cfg.setConfigPath(configFile);
        cfg.load();

        assertEquals(2.0, cfg.getIntensity(), 1e-9, "owner Intensity applied");
        MobScaleFold.DifficultyStatCurve curve = cfg.statCurveModel();
        assertEquals(0.16, curve.hpPerPoint(), 1e-9, "2.0 intensity doubles the HP slope");
        assertEquals(0.08, curve.outPerPoint(), 1e-9, "2.0 intensity doubles the out slope");
    }

    @Test
    void setIntensityRuntimeRetunesTheCurveAndClearsTheView(@TempDir Path tmp) throws Exception {
        // An owner world override that does NOT set its own Intensity inherits the runtime-tuned global.
        Path configFile = tmp.resolve("mob-scaling.json");
        Files.writeString(configFile, """
                { "WorldOverrides": [ { "Match": "arena_*" } ] }
                """);
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        cfg.setConfigPath(configFile);
        cfg.load();

        assertEquals(0.08, cfg.spawnSettingsFor("arena_1").statCurveModel().hpPerPoint(), 1e-9,
                "arena override inherits the global intensity 1.0");
        cfg.setIntensityRuntime(3.0);
        assertEquals(3.0, cfg.getIntensity(), 1e-9, "runtime intensity applied globally");
        assertEquals(0.24, cfg.spawnSettingsFor("arena_1").statCurveModel().hpPerPoint(), 1e-9,
                "the cached per-world view re-resolves the new global intensity (cache cleared)");
    }

    @Test
    void shippedDungeonDefaultsDisablePlayerScalingForIandIIandEscalationForAll() {
        MobScalingConfig cfg = freshDefaults(); // Default.json ships the 3 dungeon WorldOverrides

        SpawnScalingSettings i = cfg.spawnSettingsFor("instance-dungeon_of_fear_i");
        assertFalse(i.isPlayerScalingEnabled(), "Dungeon of Fear I has player scaling off");
        assertFalse(i.isDistanceEscalationEnabled(), "Dungeon of Fear I has escalation off");

        SpawnScalingSettings ii = cfg.spawnSettingsFor("instance-dungeon_of_fear_ii");
        assertFalse(ii.isPlayerScalingEnabled(), "Dungeon of Fear II has player scaling off");
        assertFalse(ii.isDistanceEscalationEnabled(), "Dungeon of Fear II has escalation off");

        SpawnScalingSettings iii = cfg.spawnSettingsFor("instance-dungeon_of_fear_iii");
        assertTrue(iii.isPlayerScalingEnabled(), "Dungeon of Fear III keeps player scaling on (global default)");
        assertFalse(iii.isDistanceEscalationEnabled(), "Dungeon of Fear III has escalation off");

        // A non-dungeon world matches nothing -> the global config itself (player scaling on, escalation on).
        SpawnScalingSettings overworld = cfg.spawnSettingsFor("world");
        assertTrue(overworld.isPlayerScalingEnabled(), "the overworld keeps global player scaling");
        assertTrue(overworld.isDistanceEscalationEnabled(), "the overworld keeps global escalation");
        assertTrue(overworld == cfg, "no-match returns the global config itself (zero-alloc)");
    }

    @Test
    void longestPrefixDisambiguatesTheThreeDungeons() {
        MobScalingConfig cfg = freshDefaults();
        // A suffixed instance world of each tier resolves to its OWN entry, never a shorter-prefix sibling.
        assertFalse(cfg.spawnSettingsFor("instance-dungeon_of_fear_i_ab12").isPlayerScalingEnabled(),
                "suffixed _i matches the _i* entry (player scaling off)");
        assertFalse(cfg.spawnSettingsFor("instance-dungeon_of_fear_ii_ab12").isPlayerScalingEnabled(),
                "suffixed _ii matches the _ii* entry (player scaling off), not _i*");
        assertTrue(cfg.spawnSettingsFor("instance-dungeon_of_fear_iii_ab12").isPlayerScalingEnabled(),
                "suffixed _iii matches the _iii* entry (player scaling ON), not _i*/_ii*");
    }

    @Test
    void perWorldOverlayInheritsUnsetLeavesAndOverridesSetOnes(@TempDir Path tmp) throws Exception {
        Path configFile = tmp.resolve("mob-scaling.json");
        Files.writeString(configFile, """
                { "WorldOverrides": [ {
                    "Match": "raid_*",
                    "Intensity": 2.0,
                    "RaritySpawnChance": 0.5,
                    "PlayerScalingEnabled": false,
                    "Difficulty": { "MinCap": 40.0, "StatCurve": { "OutDamagePerPoint": 0.2 } }
                } ] }
                """);
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        cfg.setConfigPath(configFile);
        cfg.load();

        SpawnScalingSettings raid = cfg.spawnSettingsFor("raid_alpha");
        assertEquals(0.5, raid.getRaritySpawnChance(), 1e-9, "override RaritySpawnChance applied");
        assertFalse(raid.isPlayerScalingEnabled(), "override PlayerScalingEnabled applied");
        assertEquals(40.0, raid.getDifficultyMinCap(), 1e-9, "override Difficulty.MinCap applied");
        assertEquals(200.0, raid.getDifficultyMaxCap(), 1e-9, "unset MaxCap inherits the global");
        // StatCurve: OutDamagePerPoint overridden (0.2) then x intensity 2.0; HpPerPoint inherits global (0.08) x 2.0.
        MobScaleFold.DifficultyStatCurve curve = raid.statCurveModel();
        assertEquals(0.4, curve.outPerPoint(), 1e-9, "override slope x override intensity");
        assertEquals(0.16, curve.hpPerPoint(), 1e-9, "inherited slope x override intensity");
        // A non-matching world is untouched (global).
        assertTrue(cfg.spawnSettingsFor("world") == cfg, "non-match returns the global config");
    }

    @Test
    void worldOverridesConcatenateAcrossLayersOwnerWinsSameMatch(@TempDir Path tmp) throws Exception {
        // The owner ADDS a new world AND overrides one shipped dungeon default by the same Match.
        Path configFile = tmp.resolve("mob-scaling.json");
        Files.writeString(configFile, """
                { "WorldOverrides": [
                    { "Match": "myworld_*", "PlayerScalingEnabled": false },
                    { "Match": "instance-dungeon_of_fear_iii*", "PlayerScalingEnabled": false }
                ] }
                """);
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        cfg.setConfigPath(configFile);
        cfg.load();

        // Owner's new world takes effect.
        assertFalse(cfg.spawnSettingsFor("myworld_1").isPlayerScalingEnabled(), "owner's new override applies");
        // The shipped dungeon defaults NOT touched by the owner still resolve (concat, not replace).
        assertFalse(cfg.spawnSettingsFor("instance-dungeon_of_fear_i").isPlayerScalingEnabled(),
                "shipped _i default survives an owner adding other overrides");
        // Owner's same-Match entry WINS over the shipped _iii default (which had player scaling ON).
        assertFalse(cfg.spawnSettingsFor("instance-dungeon_of_fear_iii").isPlayerScalingEnabled(),
                "owner same-Match override beats the shipped _iii default");
    }
}
