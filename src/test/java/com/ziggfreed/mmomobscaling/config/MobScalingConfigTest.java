package com.ziggfreed.mmomobscaling.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

    @AfterEach
    void resetWorlds() {
        WorldSettingsConfig worlds = WorldSettingsConfig.getInstance();
        worlds.setOwnerDir(null);
        worlds.applyPackLayer(Map.of());
    }

    /**
     * Feed the jar-bundled {@code Server/MmoMobScaling/Worlds/*.json} payloads into
     * {@link WorldSettingsConfig}'s pack layer (in production the engine store delivers them on
     * {@code LoadedAssetsEvent}; a unit JVM loads them straight off the test classpath). This
     * exercises the REAL shipped files (flat, self-contained; no shared Parent).
     */
    private static void loadJarWorlds() {
        Map<String, JsonObject> bodies = new LinkedHashMap<>();
        for (String name : List.of("DungeonOfFear_I", "DungeonOfFear_II",
                "DungeonOfFear_III", "KweebecNightmare")) {
            try (InputStream in = MobScalingConfigTest.class.getResourceAsStream(
                    "/Server/MmoMobScaling/Worlds/" + name + ".json")) {
                JsonObject root = JsonParser.parseString(
                        new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                bodies.put(name, root.getAsJsonObject("Payload"));
            } catch (Exception e) {
                throw new IllegalStateException("jar world file missing on the test classpath: " + name, e);
            }
        }
        WorldSettingsConfig.getInstance().applyPackLayer(bodies);
    }

    /** Write one OWNER world file (bare body) into {@code <tmp>/worlds/} and refold. */
    private static void ownerWorld(Path tmp, String id, String body) throws Exception {
        Path dir = tmp.resolve("worlds");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(id + ".json"), body, StandardCharsets.UTF_8);
        WorldSettingsConfig worlds = WorldSettingsConfig.getInstance();
        worlds.setOwnerDir(dir);
        worlds.refold();
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
        assertEquals(30.0, cfg.getDifficultyFloor(), 1e-9,
                "Difficulty.Floor default (1.0.2; the world baseline absorbed from hyMMO WorldRules)");
        assertEquals(1.0, cfg.getDifficultyMinCap(), 1e-9, "Difficulty.MinCap default");
        assertEquals(200.0, cfg.getDifficultyMaxCap(), 1e-9, "Difficulty.MaxCap default");
        assertTrue(cfg.isDistanceEscalationEnabled(), "Difficulty.DistanceEscalation.Enabled default");
        assertEquals(15000.0, cfg.getEscalationStartDistanceBlocks(), 1e-9, "escalation start default");
        assertEquals(500.0, cfg.getEscalationBlocksPerPoint(), 1e-9, "escalation slope default");
        assertEquals(199.0, cfg.getEscalationMaxBonus(), 1e-9, "escalation cap default");
        assertEquals(0.01, cfg.getEscalationRarityChancePerPoint(), 1e-9, "escalation chance-bonus default");
        // Difficulty.StatCurve: the shipped steep per-difficulty stat curve.
        assertEquals(0.08, cfg.getStatCurveHpPerPoint(), 1e-9, "StatCurve.HpPerPoint default");
        assertEquals(0.01, cfg.getStatCurveOutDamagePerPoint(), 1e-9, "StatCurve.OutDamagePerPoint default");
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
        assertEquals(15000.0, cfg.getEscalationStartDistanceBlocks(), 1e-9,
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
    // Numeric Intensity + the per-world overlay (1.0.2: Worlds/*.json files)
    // ---------------------------------------------------------------------

    @Test
    void intensityScalesTheStatCurveSlopes() {
        MobScalingConfig cfg = freshDefaults(); // Intensity default 1.0
        MobScaleFold.DifficultyStatCurve curve = cfg.statCurveModel();
        assertEquals(0.08, curve.hpPerPoint(), 1e-9, "intensity 1.0 leaves the slope unchanged");
        assertEquals(0.01, curve.outPerPoint(), 1e-9, "intensity 1.0 leaves the out slope unchanged");
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
        assertEquals(0.02, curve.outPerPoint(), 1e-9, "2.0 intensity doubles the out slope");
    }

    @Test
    void setIntensityRuntimeRetunesTheCurveAndClearsTheView(@TempDir Path tmp) throws Exception {
        // An owner world FILE that does NOT set its own Intensity inherits the runtime-tuned global.
        MobScalingConfig cfg = freshDefaults();
        ownerWorld(tmp, "arena", "{ \"Match\": \"arena_*\" }");

        assertEquals(0.08, cfg.spawnSettingsFor("arena_1").statCurveModel().hpPerPoint(), 1e-9,
                "arena world file inherits the global intensity 1.0");
        cfg.setIntensityRuntime(3.0);
        assertEquals(3.0, cfg.getIntensity(), 1e-9, "runtime intensity applied globally");
        assertEquals(0.24, cfg.spawnSettingsFor("arena_1").statCurveModel().hpPerPoint(), 1e-9,
                "the cached per-world view re-resolves the new global intensity (cache cleared)");
    }

    @Test
    void shippedDungeonWorldsDecodeToTheirFlatPolicies() {
        MobScalingConfig cfg = freshDefaults();
        loadJarWorlds(); // the jar Worlds/*.json files (flat, self-contained; no shared Parent)

        // Dungeon of Fear I and II simply turn open-world scaling OFF in their instances.
        assertFalse(cfg.spawnSettingsFor("instance-dungeon_of_fear_i").isWorldScalingEnabled(),
                "Dungeon of Fear I turns scaling off");
        assertFalse(cfg.spawnSettingsFor("instance-dungeon_of_fear_ii").isWorldScalingEnabled(),
                "Dungeon of Fear II turns scaling off");

        // Dungeon of Fear III keeps scaling ON (player scaling on, global default) but with distance escalation off.
        SpawnScalingSettings iii = cfg.spawnSettingsFor("instance-dungeon_of_fear_iii");
        assertTrue(iii.isWorldScalingEnabled(), "Dungeon of Fear III keeps scaling on");
        assertTrue(iii.isPlayerScalingEnabled(), "Dungeon of Fear III keeps player scaling on (global default)");
        assertFalse(iii.isDistanceEscalationEnabled(), "Dungeon of Fear III turns distance escalation off");

        // The Kweebec Nightmare world file is the per-world kill-switch (absorbed from hyMMO WorldRules).
        assertFalse(cfg.spawnSettingsFor("KweebecNightmare_run7").isWorldScalingEnabled(),
                "the Kweebec world file turns scaling off there");

        // A non-dungeon world matches nothing -> the global config itself (player scaling on, escalation on).
        SpawnScalingSettings overworld = cfg.spawnSettingsFor("world");
        assertTrue(overworld.isPlayerScalingEnabled(), "the overworld keeps global player scaling");
        assertTrue(overworld.isDistanceEscalationEnabled(), "the overworld keeps global escalation");
        assertTrue(overworld == cfg, "no-match returns the global config itself (zero-alloc)");
    }

    @Test
    void longestPrefixDisambiguatesTheThreeDungeons() {
        MobScalingConfig cfg = freshDefaults();
        loadJarWorlds();
        // A suffixed instance world of each tier resolves to its OWN entry, never a shorter-prefix sibling.
        // I and II turn scaling off; III keeps it on, so a _iii mis-resolved to _i*/_ii* would read scaling OFF.
        assertFalse(cfg.spawnSettingsFor("instance-dungeon_of_fear_i_ab12").isWorldScalingEnabled(),
                "suffixed _i matches the _i* entry (scaling off)");
        assertFalse(cfg.spawnSettingsFor("instance-dungeon_of_fear_ii_ab12").isWorldScalingEnabled(),
                "suffixed _ii matches the _ii* entry (scaling off)");
        assertTrue(cfg.spawnSettingsFor("instance-dungeon_of_fear_iii_ab12").isWorldScalingEnabled(),
                "suffixed _iii matches the _iii* entry (scaling ON), not the shorter _i*/_ii*");
    }

    @Test
    void perWorldFileInheritsUnsetLeavesAndOverridesSetOnes(@TempDir Path tmp) throws Exception {
        MobScalingConfig cfg = freshDefaults();
        ownerWorld(tmp, "raid", """
                {
                    "Match": "raid_*",
                    "Enabled": true,
                    "Intensity": 2.0,
                    "RaritySpawnChance": 0.5,
                    "OpenWorld": { "PlayerScalingEnabled": false },
                    "Difficulty": { "Floor": 55.0, "MinCap": 40.0, "StatCurve": { "OutDamagePerPoint": 0.2 } },
                    "Pool": { "Rarities": { "Deny": ["legendary"] },
                              "Variants": { "ChanceMultiplier": 2.0 },
                              "Affixes": { "ExtraSlots": 1 } }
                }
                """);

        SpawnScalingSettings raid = cfg.spawnSettingsFor("raid_alpha");
        assertEquals(0.5, raid.getRaritySpawnChance(), 1e-9, "world RaritySpawnChance applied");
        assertFalse(raid.isPlayerScalingEnabled(), "world OpenWorld.PlayerScalingEnabled applied");
        assertEquals(55.0, raid.getDifficultyFloor(), 1e-9, "world Difficulty.Floor applied");
        assertEquals(40.0, raid.getDifficultyMinCap(), 1e-9, "world Difficulty.MinCap applied");
        assertEquals(200.0, raid.getDifficultyMaxCap(), 1e-9, "unset MaxCap inherits the global");
        assertTrue(raid.isWorldScalingEnabled(), "world Enabled=true keeps scaling on");
        // The per-world Pool gates + dials (1.0.2).
        assertFalse(raid.isRarityAllowed("Legendary"), "denied rarity is gated out (case-insensitive)");
        assertTrue(raid.isRarityAllowed("rare"), "an unlisted rarity still rolls (deny-list only)");
        assertEquals(2.0, raid.getVariantChanceMultiplier(), 1e-9, "world variant chance multiplier");
        assertEquals(1, raid.getExtraAffixSlots(), "world extra affix slots");
        // StatCurve: OutDamagePerPoint overridden (0.2) then x intensity 2.0; HpPerPoint inherits global (0.08) x 2.0.
        MobScaleFold.DifficultyStatCurve curve = raid.statCurveModel();
        assertEquals(0.4, curve.outPerPoint(), 1e-9, "override slope x override intensity");
        assertEquals(0.16, curve.hpPerPoint(), 1e-9, "inherited slope x override intensity");
        // A non-matching world is untouched (global: allow-all pool, neutral dials).
        assertTrue(cfg.spawnSettingsFor("world") == cfg, "non-match returns the global config");
        assertTrue(cfg.isRarityAllowed("legendary"), "the global view has no pool gate");
    }

    @Test
    void ownerWorldFileReplacesTheShippedFileByIdAndAddsNewOnes(@TempDir Path tmp) throws Exception {
        MobScalingConfig cfg = freshDefaults();
        loadJarWorlds();
        // The owner ADDS a new world file AND replaces one shipped dungeon file BY ID (the owner file
        // stem matches the jar file stem case-insensitively; layering is id-replace, not per-leaf merge).
        Path dir = tmp.resolve("worlds");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("myworld.json"),
                "{ \"Match\": \"myworld_*\", \"OpenWorld\": { \"PlayerScalingEnabled\": false } }");
        Files.writeString(dir.resolve("dungeonoffear_iii.json"),
                "{ \"Match\": \"instance-dungeon_of_fear_iii*\", \"OpenWorld\": { \"PlayerScalingEnabled\": false } }");
        WorldSettingsConfig worlds = WorldSettingsConfig.getInstance();
        worlds.setOwnerDir(dir);
        worlds.refold();

        // Owner's new world takes effect.
        assertFalse(cfg.spawnSettingsFor("myworld_1").isPlayerScalingEnabled(), "owner's new world file applies");
        // The shipped dungeon files NOT touched by the owner still resolve.
        assertFalse(cfg.spawnSettingsFor("instance-dungeon_of_fear_i").isWorldScalingEnabled(),
                "shipped _i file survives an owner adding other files (still scaling-off)");
        // Owner's same-id file REPLACES the shipped _iii wholesale: player scaling now off there, and the
        // shipped file's escalation-off policy is GONE because the owner body does not carry it.
        assertFalse(cfg.spawnSettingsFor("instance-dungeon_of_fear_iii").isPlayerScalingEnabled(),
                "owner same-id file beats the shipped _iii file");
        assertTrue(cfg.spawnSettingsFor("instance-dungeon_of_fear_iii").isDistanceEscalationEnabled(),
                "the replace is WHOLESALE: the shipped file's escalation-off does not leak into the owner body");
    }

    @Test
    void legacyInlineWorldOverridesMigrateToOwnerWorldFiles(@TempDir Path tmp) throws Exception {
        // A SHIPPED-1.0.1 owner file: inline WorldOverrides (top-level PlayerScalingEnabled included).
        Path configFile = tmp.resolve("mob-scaling.json");
        Files.writeString(configFile, """
                { "Intensity": 2.0, "WorldOverrides": [
                    { "Match": "arena_*", "Intensity": 3.0, "PlayerScalingEnabled": false }
                ] }
                """);
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        cfg.setConfigPath(configFile);
        cfg.load();
        WorldSettingsConfig worlds = WorldSettingsConfig.getInstance();
        worlds.setOwnerDir(tmp.resolve("worlds"));
        assertTrue(worlds.migrateLegacyOwnerOverrides(configFile), "a legacy array triggers the migration");
        worlds.refold();
        cfg.refreshFromDisk();

        // The entry became its own file with the 1.0.2 schema (PlayerScalingEnabled under OpenWorld).
        assertTrue(Files.exists(tmp.resolve("worlds").resolve("arena.json")),
                "the sanitized match (wildcard + trailing separators dropped) becomes the file stem");
        SpawnScalingSettings arena = cfg.spawnSettingsFor("arena_1");
        assertFalse(arena.isPlayerScalingEnabled(), "migrated toggle moved under OpenWorld");
        assertEquals(0.24, arena.statCurveModel().hpPerPoint(), 1e-9,
                "migrated per-world Intensity 3.0 applies (0.08 x 3.0)");
        // The owner file keeps its other keys but the array is stripped; a second boot is a no-op.
        String body = Files.readString(configFile, StandardCharsets.UTF_8);
        assertTrue(body.contains("\"Intensity\": 2.0"), "sibling owner keys survive the strip: " + body);
        assertFalse(body.contains("WorldOverrides"), "the legacy array is stripped: " + body);
        assertFalse(worlds.migrateLegacyOwnerOverrides(configFile), "idempotent: nothing left to migrate");
    }
}
