package com.ziggfreed.mmomobscaling.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonParser;
import com.ziggfreed.mmomobscaling.asset.WorldSettings;

/**
 * Unit tests for {@link WorldSettingsConfig}: the owner-dir scan (bare body canonical, a pack-style
 * {@code Payload} wrapper peeled, a malformed file skipped without poisoning the fold), the
 * cross-layer {@code Parent} chain (an owner file inheriting a pack base), owner-replaces-pack by id,
 * and the filename sanitizer. The end-to-end fold + migration are exercised in
 * {@link MobScalingConfigTest}.
 */
class WorldSettingsConfigTest {

    @AfterEach
    void reset() {
        WorldSettingsConfig worlds = WorldSettingsConfig.getInstance();
        worlds.setOwnerDir(null);
        worlds.applyPackLayer(Map.of());
    }

    private static WorldSettingsConfig scan(Path dir) {
        WorldSettingsConfig worlds = WorldSettingsConfig.getInstance();
        worlds.setOwnerDir(dir);
        worlds.refold();
        return worlds;
    }

    @Test
    void bareAndPayloadWrappedOwnerFilesBothScan(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("bare.json"), "{ \"Match\": \"bare_*\", \"Intensity\": 2.0 }");
        Files.writeString(tmp.resolve("wrapped.json"),
                "{ \"Name\": \"copy-pasted from a pack\", \"Payload\": { \"Match\": \"wrapped_*\" } }");
        WorldSettingsConfig worlds = scan(tmp);

        assertEquals(2.0, worlds.effectiveById("bare").getIntensity(), 1e-9, "bare body is canonical");
        assertNotNull(worlds.resolve("wrapped_7"), "a pack-style Payload wrapper is peeled");
        assertTrue(worlds.ownerAuthoredIds().containsAll(java.util.Set.of("bare", "wrapped")));
    }

    @Test
    void malformedOwnerFileIsSkippedNotPoisoning(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("good.json"), "{ \"Match\": \"good_*\" }");
        Files.writeString(tmp.resolve("broken.json"), "{ not json !!");
        WorldSettingsConfig worlds = scan(tmp);

        assertNotNull(worlds.resolve("good_1"), "the good file still folds");
        assertNull(worlds.effectiveById("broken"), "the malformed file is skipped");
    }

    @Test
    void ownerFileMayParentAPackBaseAndReplacesAPackFileById(@TempDir Path tmp) throws Exception {
        WorldSettingsConfig worlds = WorldSettingsConfig.getInstance();
        worlds.applyPackLayer(Map.of(
                "Shared_Base", JsonParser.parseString(
                        "{ \"Difficulty\": { \"DistanceEscalation\": { \"Enabled\": false } } }").getAsJsonObject(),
                "Shipped", JsonParser.parseString(
                        "{ \"Match\": \"shipped_*\", \"Intensity\": 5.0 }").getAsJsonObject()));
        Files.writeString(tmp.resolve("mine.json"),
                "{ \"Match\": \"mine_*\", \"Parent\": \"Shared_Base\", \"Intensity\": 2.0 }",
                StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("shipped.json"), "{ \"Match\": \"shipped_*\", \"Intensity\": 1.5 }");
        worlds.setOwnerDir(tmp);
        worlds.refold();

        // Cross-layer Parent: the OWNER file inherits the PACK base's escalation-off leaf.
        WorldSettings mine = worlds.effectiveById("mine");
        assertEquals(2.0, mine.getIntensity(), 1e-9);
        assertEquals(Boolean.FALSE, mine.getDifficulty().getDistanceEscalation().getEnabled(),
                "the pack base's leaf is inherited across layers");
        assertEquals("Shared_Base", worlds.parentOf("mine"), "the authored Parent is exposed for the UI");
        // Owner replaces pack wholesale by id.
        assertEquals(1.5, worlds.effectiveById("shipped").getIntensity(), 1e-9,
                "the owner file replaces the same-id pack body wholesale");
        // A base with no Match is never emitted as a matcher entry.
        assertNull(worlds.resolve("shared_base"), "a pool-only base never matches a world");
    }

    @Test
    void sanitizeFileIdDropsWildcardsAndSeparators() {
        assertEquals("instance-dungeon_of_fear_i", WorldSettingsConfig.sanitizeFileId("instance-dungeon_of_fear_i*"));
        assertEquals("arena", WorldSettingsConfig.sanitizeFileId(" Arena_* "));
        assertEquals("a_b", WorldSettingsConfig.sanitizeFileId("a/b"));
        assertEquals("world", WorldSettingsConfig.sanitizeFileId("***"));
    }
}
