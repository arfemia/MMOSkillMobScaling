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
    void saveIntensityPersistsRefoldsAndKeepsComment(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("mob-scaling.json");
        MobScalingConfig cfg = loaded(file);

        assertTrue(MobScalingOwnerWriter.saveIntensity(2.0));

        String body = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(body.contains("\"Intensity\": 2.0"), body);
        assertTrue(body.contains("$Comment"), "scaffold $Comment preserved: " + body);
        assertEquals(2.0, cfg.getIntensity(), 1e-9, "live config reflects the persisted value");
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
        leaves.put("Match", "arena_*");
        leaves.put("Intensity", 3.0);
        leaves.put("OpenWorld.PlayerScalingEnabled", Boolean.FALSE);
        leaves.put("Difficulty.MinCap", 60.0);
        assertTrue(MobScalingOwnerWriter.saveWorldFile("arena", leaves));
        assertTrue(Files.exists(tmp.resolve("worlds").resolve("arena.json")), "one file per world rule");

        // The folded view + the per-world spawn settings reflect the new owner file.
        WorldSettings ws = worlds.effectiveById("arena");
        assertNotNull(ws);
        assertEquals("arena_*", ws.getMatch());
        assertEquals(3.0, ws.getIntensity(), 1e-9);
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
    void saveWorldFileMergesPartiallyAndLeavesOtherFilesIntact(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("mob-scaling.json");
        loaded(file);
        WorldSettingsConfig worlds = WorldSettingsConfig.getInstance();
        worlds.setOwnerDir(tmp.resolve("worlds"));
        worlds.refold();

        MobScalingOwnerWriter.saveWorldFile("world_a", Map.of("Match", "world_a*", "Intensity", 1.5));
        MobScalingOwnerWriter.saveWorldFile("world_b", Map.of("Match", "world_b*", "Intensity", 2.5));
        // Re-save world_a with a new Intensity: a PARTIAL merge into its own file; world_b untouched.
        MobScalingOwnerWriter.saveWorldFile("world_a", Map.of("Intensity", 4.0));

        assertEquals(4.0, worlds.effectiveById("world_a").getIntensity(), 1e-9);
        assertEquals("world_a*", worlds.effectiveById("world_a").getMatch(), "unwritten leaf survives the merge");
        WorldSettings b = worlds.effectiveById("world_b");
        assertNotNull(b, "the other owner world file is preserved");
        assertEquals(2.5, b.getIntensity(), 1e-9);
    }

    @Test
    void saveWorldFileNullLeafRemovesIt(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("mob-scaling.json");
        loaded(file);
        WorldSettingsConfig worlds = WorldSettingsConfig.getInstance();
        worlds.setOwnerDir(tmp.resolve("worlds"));
        worlds.refold();

        MobScalingOwnerWriter.saveWorldFile("arena", Map.of("Match", "arena_*", "Intensity", 3.0));
        Map<String, Object> clear = new LinkedHashMap<>();
        clear.put("Intensity", null); // blank editor field / Inherit -> remove the leaf
        MobScalingOwnerWriter.saveWorldFile("arena", clear);

        assertNull(worlds.effectiveById("arena").getIntensity(), "removed leaf inherits again");
        String body = Files.readString(tmp.resolve("worlds").resolve("arena.json"), StandardCharsets.UTF_8);
        assertFalse(body.contains("Intensity"), body);
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
                        "{ \"Match\": \"shipped_*\", \"Parent\": \"Shared_Base\", \"Intensity\": 5.0, "
                      + "\"InspectorHud\": { \"RangeBlocks\": 20.0 } }").getAsJsonObject()));
        assertTrue(worlds.ownerAuthoredIds().isEmpty(), "no owner file for shipped_world yet");

        // A UI-style save: only the exposed leaves the admin form collected, with Intensity blanked
        // (Inherit) - the pre-fix bug dropped everything else the shipped body authored.
        Map<String, Object> uiLeaves = new LinkedHashMap<>();
        uiLeaves.put("Match", "shipped_*");
        uiLeaves.put("Intensity", null);
        assertTrue(MobScalingOwnerWriter.saveWorldFile("shipped_world", uiLeaves));

        Path ownerFile = tmp.resolve("worlds").resolve("shipped_world.json");
        String body = Files.readString(ownerFile, StandardCharsets.UTF_8);
        assertTrue(body.contains("\"RangeBlocks\": 20.0"), "unexposed leaf survives the seed: " + body);
        assertTrue(body.contains("\"Parent\": \"Shared_Base\""), "Parent survives the seed: " + body);
        assertFalse(body.contains("\"Intensity\""), "the blanked EXPOSED leaf is removed: " + body);

        WorldSettings ws = worlds.effectiveById("shipped_world");
        assertNotNull(ws);
        assertNotNull(ws.getInspectorHud());
        assertEquals(20.0, ws.getInspectorHud().getRangeBlocks(), 1e-9, "unexposed leaf still decodes");
        assertNull(ws.getIntensity(), "blanked exposed leaf is gone (falls through to the parent/global)");
        assertEquals(Boolean.FALSE, ws.getDifficulty().getDistanceEscalation().getEnabled(),
                "the Parent chain still resolves after the seed");
        assertTrue(worlds.ownerAuthoredIds().contains("shipped_world"), "now badged as owner-authored");
    }
}
