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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ziggfreed.mmomobscaling.asset.MobScalingSettingsAsset.WorldOverride;

/**
 * Integration of the write-back path both the admin UI and {@code /mobscaling} use: a
 * {@link MobScalingOwnerWriter} save writes the owner file (partial, preserving {@code $Comment}) with
 * the right number TYPE, then {@code MobScalingConfig.refreshFromDisk} folds it so the live config
 * reflects the change with no restart. Asserts on the file TEXT (the mod has no gson) + the config state.
 */
class MobScalingOwnerWriterTest {

    private static MobScalingConfig loaded(Path file) {
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        cfg.setConfigPath(file);
        cfg.load(); // seeds jar defaults + scaffolds an empty owner file with a $Comment
        return cfg;
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
    void upsertAndRemoveWorldOverrideFoldsLive(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("mob-scaling.json");
        MobScalingConfig cfg = loaded(file);

        Map<String, Object> leaves = new LinkedHashMap<>();
        leaves.put("Intensity", 3.0);
        leaves.put("PlayerScalingEnabled", Boolean.FALSE);
        leaves.put("Difficulty.MinCap", 60.0);
        assertTrue(MobScalingOwnerWriter.upsertWorldOverride("arena_*", leaves));

        // The folded view + effective lookup reflect the new owner override.
        List<WorldOverride> view = cfg.worldOverrideView();
        assertTrue(view.stream().anyMatch(o -> "arena_*".equals(o.getMatch())), "override folds into the view");
        WorldOverride ov = cfg.effectiveWorldOverride("arena_*");
        assertNotNull(ov);
        assertEquals(3.0, ov.getIntensity(), 1e-9);
        assertEquals(Boolean.FALSE, ov.getPlayerScalingEnabled());
        assertNotNull(ov.getDifficulty());
        assertEquals(60.0, ov.getDifficulty().getMinCap(), 1e-9);
        assertTrue(MobScalingOwnerWriter.ownerAuthoredMatches().contains("arena_*"), "badged as owner-authored");

        // Removing it folds it back out.
        assertTrue(MobScalingOwnerWriter.removeWorldOverride("arena_*"));
        assertNull(cfg.effectiveWorldOverride("arena_*"));
        assertFalse(MobScalingOwnerWriter.ownerAuthoredMatches().contains("arena_*"));
    }

    @Test
    void upsertLeavesOtherOwnerOverridesIntact(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("mob-scaling.json");
        MobScalingConfig cfg = loaded(file);

        MobScalingOwnerWriter.upsertWorldOverride("world_a", Map.of("Intensity", 1.5));
        MobScalingOwnerWriter.upsertWorldOverride("world_b", Map.of("Intensity", 2.5));
        // Re-upsert world_a; world_b must survive (whole-element replace by Match).
        MobScalingOwnerWriter.upsertWorldOverride("world_a", Map.of("Intensity", 4.0));

        assertEquals(4.0, cfg.effectiveWorldOverride("world_a").getIntensity(), 1e-9);
        WorldOverride b = cfg.effectiveWorldOverride("world_b");
        assertNotNull(b, "the other owner override is preserved");
        assertEquals(2.5, b.getIntensity(), 1e-9);
    }
}
