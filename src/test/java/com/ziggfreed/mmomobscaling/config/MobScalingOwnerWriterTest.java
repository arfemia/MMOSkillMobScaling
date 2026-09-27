package com.ziggfreed.mmomobscaling.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonParser;
import com.ziggfreed.mmomobscaling.asset.WorldSettings;

/**
 * Integration of the write-back path both the admin UI and {@code /mobscaling} use: a
 * {@link MobScalingOwnerWriter} save writes the owner file (partial, preserving {@code $Comment}) with
 * the right number TYPE, then {@code MobScalingConfig.refreshFromDisk} folds it so the live config
 * reflects the change with no restart; a per-WORLD save writes its own {@code worlds/<id>.json}
 * (1.0.2) and refolds {@link WorldSettingsConfig}. Asserts on the file TEXT + the config state.
 */
class MobScalingOwnerWriterTest {

    private static MobScalingConfig loaded(Path file) {
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        cfg.setConfigPath(file);
        cfg.load(); // seeds jar defaults + scaffolds an empty owner file with a $Comment
        return cfg;
    }

    @AfterEach
    void resetWorlds() {
        WorldSettingsConfig worlds = WorldSettingsConfig.getInstance();
        worlds.setOwnerDir(null);
        worlds.applyPackLayer(Map.of());
    }

    @Test
    void saveRaritySpawnChancePersistsRefoldsAndKeepsComment(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("mob-scaling.json");
        MobScalingConfig cfg = loaded(file);

        assertTrue(MobScalingOwnerWriter.saveRaritySpawnChance(0.5));

        String body = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(body.contains("\"RaritySpawnChance\": 0.5"), body);
        assertTrue(body.contains("$Comment"), "scaffold $Comment preserved: " + body);
        assertEquals(0.5, cfg.getRaritySpawnChance(), 1e-9, "live config reflects the persisted value");
    }

    @Test
    void saveZoneHudPositionWritesIntegerOffsetsAndRefolds(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("mob-scaling.json");
        MobScalingConfig cfg = loaded(file);

        assertTrue(MobScalingOwnerWriter.saveZoneHudPosition("TOP_RIGHT", 40, 12));

        String body = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(body.contains("\"OffsetX\": 40"), body);
        assertFalse(body.contains("40.0"), "an INTEGER leaf must not serialize as a double: " + body);
        assertEquals("TOP_RIGHT", cfg.getZoneHudPosition());
        assertEquals(40, cfg.getZoneHudOffsetX());
        assertEquals(12, cfg.getZoneHudOffsetY());
    }

    @Test
    void saveWorldFileWritesFoldsAndDeletes(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("mob-scaling.json");
        MobScalingConfig cfg = loaded(file);
        WorldSettingsConfig worlds = WorldSettingsConfig.getInstance();
        worlds.setOwnerDir(tmp.resolve("worlds"));
        worlds.refold();

        Map<String, Object> leaves = new LinkedHashMap<>();
        leaves.put(MobScalingOwnerWriter.WHERE_MATCH, List.of("arena_*"));
        leaves.put("RaritySpawnChance", 0.3);
        leaves.put("OpenWorld.PlayerScalingEnabled", Boolean.FALSE);
        leaves.put("Difficulty.MinCap", 60.0);
        assertTrue(MobScalingOwnerWriter.saveWorldFile("arena", leaves));
        assertTrue(Files.exists(tmp.resolve("worlds").resolve("arena.json")), "one file per world rule");

        // The folded view + the per-world spawn settings reflect the new owner file.
        WorldSettings ws = worlds.effectiveById("arena");
        assertNotNull(ws);
        assertEquals("arena_*", ws.firstMatchPattern());
        assertEquals(0.3, ws.getRaritySpawnChance(), 1e-9);
        SpawnScalingSettings view = cfg.spawnSettingsFor("arena_pvp7");
        assertFalse(view.isPlayerScalingEnabled(), "OpenWorld.PlayerScalingEnabled applies per world");
        assertEquals(60.0, view.getDifficultyMinCap(), 1e-9);
        assertTrue(MobScalingOwnerWriter.ownerAuthoredIds().contains("arena"), "badged as owner-authored");

        // Deleting the file folds it back out (and the cached view drops).
        assertTrue(MobScalingOwnerWriter.deleteWorldFile("arena"));
        assertNull(worlds.effectiveById("arena"));
        assertFalse(MobScalingOwnerWriter.ownerAuthoredIds().contains("arena"));
        assertTrue(cfg.spawnSettingsFor("arena_pvp7") == cfg, "no rule left: the global config stands");
    }

    @Test
    void saveAndDeleteReachAHandCasedOwnerWorldFile(@TempDir Path tmp) throws Exception {
        // An owner hand-named Arena.json: the fold reads it under "arena", and a save or delete for "arena"
        // must land on THAT file rather than on a second, lower-cased one beside it.
        Path file = tmp.resolve("mob-scaling.json");
        MobScalingConfig cfg = loaded(file);
        Path worldsDir = tmp.resolve("worlds");
        Files.createDirectories(worldsDir);
        Files.writeString(worldsDir.resolve("Arena.json"),
                "{ \"Where\": { \"Match\": [\"arena_*\"] }, \"RaritySpawnChance\": 0.3 }", StandardCharsets.UTF_8);
        WorldSettingsConfig worlds = WorldSettingsConfig.getInstance();
        worlds.setOwnerDir(worldsDir);
        worlds.refold();
        assertEquals(0.3, cfg.spawnSettingsFor("arena_1").getRaritySpawnChance(), 1e-9, "the hand-cased file folds");
        assertEquals("Arena.json", worlds.ownerFileFor("arena").getFileName().toString(), "and is the file for the id");

        assertTrue(MobScalingOwnerWriter.saveWorldFile("arena", Map.of("RaritySpawnChance", 0.4)));
        assertEquals(1, jsonFilesIn(worldsDir), "the save reached the existing file; no second file was created");
        assertTrue(Files.readString(worldsDir.resolve("Arena.json"), StandardCharsets.UTF_8).contains("0.4"));
        assertEquals(0.4, cfg.spawnSettingsFor("arena_1").getRaritySpawnChance(), 1e-9);

        assertTrue(MobScalingOwnerWriter.deleteWorldFile("arena"));
        assertEquals(0, jsonFilesIn(worldsDir), "the delete removed the hand-cased file");
        assertEquals(cfg.getRaritySpawnChance(), cfg.spawnSettingsFor("arena_1").getRaritySpawnChance(), 1e-9,
                "the world falls back to the global");
    }

    private static long jsonFilesIn(Path dir) throws Exception {
        try (var files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json")).count();
        }
    }

    @Test
    void saveWorldFileMergesPartiallyAndLeavesOtherFilesIntact(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("mob-scaling.json");
        loaded(file);
        WorldSettingsConfig worlds = WorldSettingsConfig.getInstance();
        worlds.setOwnerDir(tmp.resolve("worlds"));
        worlds.refold();

        MobScalingOwnerWriter.saveWorldFile("world_a", Map.of(MobScalingOwnerWriter.WHERE_MATCH, List.of("world_a*"), "RaritySpawnChance", 0.15));
        MobScalingOwnerWriter.saveWorldFile("world_b", Map.of(MobScalingOwnerWriter.WHERE_MATCH, List.of("world_b*"), "RaritySpawnChance", 0.25));
        // Re-save world_a with a new RaritySpawnChance: a PARTIAL merge into its own file; world_b untouched.
        MobScalingOwnerWriter.saveWorldFile("world_a", Map.of("RaritySpawnChance", 0.4));

        assertEquals(0.4, worlds.effectiveById("world_a").getRaritySpawnChance(), 1e-9);
        assertEquals("world_a*", worlds.effectiveById("world_a").firstMatchPattern(),
                "unwritten leaf survives the merge");
        WorldSettings b = worlds.effectiveById("world_b");
        assertNotNull(b, "the other owner world file is preserved");
        assertEquals(0.25, b.getRaritySpawnChance(), 1e-9);
    }

    @Test
    void saveWorldFileNullLeafRemovesIt(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("mob-scaling.json");
        loaded(file);
        WorldSettingsConfig worlds = WorldSettingsConfig.getInstance();
        worlds.setOwnerDir(tmp.resolve("worlds"));
        worlds.refold();

        MobScalingOwnerWriter.saveWorldFile("arena", Map.of("Match", "arena_*", "RaritySpawnChance", 0.3));
        Map<String, Object> clear = new LinkedHashMap<>();
        clear.put("RaritySpawnChance", null); // blank editor field / Inherit -> remove the leaf
        MobScalingOwnerWriter.saveWorldFile("arena", clear);

        assertNull(worlds.effectiveById("arena").getRaritySpawnChance(), "removed leaf inherits again");
        String body = Files.readString(tmp.resolve("worlds").resolve("arena.json"), StandardCharsets.UTF_8);
        assertFalse(body.contains("RaritySpawnChance"), body);
    }

    @Test
    void saveWorldFileSeedsFromShippedBodyOnFirstOverrideKeepingUnexposedLeaves(@TempDir Path tmp) throws Exception {
        WorldSettingsConfig worlds = WorldSettingsConfig.getInstance();
        worlds.setOwnerDir(tmp.resolve("worlds"));
        // A jar/pack-shipped world with a Parent AND a leaf the admin UI does NOT expose per world
        // (InspectorHud.RangeBlocks) - no owner file exists for it yet.
        worlds.applyPackLayer(Map.of(
                "Shared_Base", JsonParser.parseString(
                        "{ \"Difficulty\": { \"DistanceEscalation\": { \"Enabled\": false } } }").getAsJsonObject(),
                "shipped_world", JsonParser.parseString(
                        "{ \"Where\": { \"Match\": [\"shipped_*\"] }, \"Parent\": \"Shared_Base\", \"RaritySpawnChance\": 0.5, "
                      + "\"InspectorHud\": { \"RangeBlocks\": 20.0 } }").getAsJsonObject()));
        assertTrue(worlds.ownerAuthoredIds().isEmpty(), "no owner file for shipped_world yet");

        // A UI-style save: only the exposed leaves the admin form collected, with RaritySpawnChance blanked
        // (Inherit) - the pre-fix bug dropped everything else the shipped body authored.

        Map<String, Object> uiLeaves = new LinkedHashMap<>();
        uiLeaves.put("Match", "shipped_*");
        uiLeaves.put("RaritySpawnChance", null);
        assertTrue(MobScalingOwnerWriter.saveWorldFile("shipped_world", uiLeaves));

        Path ownerFile = tmp.resolve("worlds").resolve("shipped_world.json");
        String body = Files.readString(ownerFile, StandardCharsets.UTF_8);
        assertTrue(body.contains("\"RangeBlocks\": 20.0"), "unexposed leaf survives the seed: " + body);
        assertTrue(body.contains("\"Parent\": \"Shared_Base\""), "Parent survives the seed: " + body);
        assertFalse(body.contains("\"RaritySpawnChance\""), "the blanked EXPOSED leaf is removed: " + body);

        WorldSettings ws = worlds.effectiveById("shipped_world");
        assertNotNull(ws);
        assertNotNull(ws.getInspectorHud());
        assertEquals(20.0, ws.getInspectorHud().getRangeBlocks(), 1e-9, "unexposed leaf still decodes");
        assertNull(ws.getRaritySpawnChance(), "blanked exposed leaf is gone (falls through to the parent/global)");
        assertEquals(Boolean.FALSE, ws.getDifficulty().getDistanceEscalation().getEnabled(),
                "the Parent chain still resolves after the seed");
        assertTrue(worlds.ownerAuthoredIds().contains("shipped_world"), "now badged as owner-authored");
    }
}
