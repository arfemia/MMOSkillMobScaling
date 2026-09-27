package com.ziggfreed.mmomobscaling.pages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.mmomobscaling.asset.WorldSettings;

/**
 * The per-world form's round trip for a TEXT leaf whose empty string is a value: a world file that
 * deliberately authors an empty zone or biome name-key prefix keeps it across a Save, while a blank
 * field over an authored NON-empty prefix (or over nothing) still means inherit.
 */
class WorldFormLeavesTest {

    private static WorldSettings authored(String json) throws Exception {
        return WorldSettings.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    /** What {@code SettingsForm.collectLeaves(true)} hands over for the two prefix fields left blank. */
    private static Map<String, Object> blankPrefixes() {
        Map<String, Object> leaves = new LinkedHashMap<>();
        leaves.put(WorldFormLeaves.ZONE_PREFIX_LEAF, null);
        leaves.put(WorldFormLeaves.BIOME_PREFIX_LEAF, null);
        leaves.put("Difficulty.Floor", 45.0);
        return leaves;
    }

    @Test
    void anAuthoredEmptyPrefixSurvivesASaveWithTheFieldLeftBlank() throws Exception {
        WorldSettings file = authored("{ \"Where\": { \"Match\": [\"a_*\"] },"
                + " \"ZoneHud\": { \"ZoneNameKeyPrefix\": \"\", \"BiomeNameKeyPrefix\": \"my.biome.\" } }");
        Map<String, Object> leaves = blankPrefixes();
        WorldFormLeaves.keepAuthoredEmptyText(leaves, file);
        assertEquals("", leaves.get(WorldFormLeaves.ZONE_PREFIX_LEAF),
                "the authored empty zone prefix is written back as the empty string, not removed");
        assertTrue(leaves.containsKey(WorldFormLeaves.BIOME_PREFIX_LEAF));
        assertNull(leaves.get(WorldFormLeaves.BIOME_PREFIX_LEAF),
                "a blank over an authored NON-empty biome prefix still means inherit (the leaf is removed)");
        assertEquals(45.0, leaves.get("Difficulty.Floor"), "every other leaf is untouched");
    }

    @Test
    void aTypedPrefixIsNeverOverridden() throws Exception {
        WorldSettings file = authored("{ \"ZoneHud\": { \"ZoneNameKeyPrefix\": \"\" } }");
        Map<String, Object> leaves = new LinkedHashMap<>();
        leaves.put(WorldFormLeaves.ZONE_PREFIX_LEAF, "server.map.region.");
        WorldFormLeaves.keepAuthoredEmptyText(leaves, file);
        assertEquals("server.map.region.", leaves.get(WorldFormLeaves.ZONE_PREFIX_LEAF),
                "a value the admin typed wins over the file");
    }

    @Test
    void nothingAuthoredMeansBlankStaysInherit() throws Exception {
        Map<String, Object> fresh = blankPrefixes();
        WorldFormLeaves.keepAuthoredEmptyText(fresh, null);
        assertNull(fresh.get(WorldFormLeaves.ZONE_PREFIX_LEAF), "a brand-new file has nothing to keep");

        WorldSettings noHud = authored("{ \"Where\": { \"Match\": [\"b_*\"] }, \"Difficulty\": { \"Floor\": 3.0 } }");
        Map<String, Object> leaves = blankPrefixes();
        WorldFormLeaves.keepAuthoredEmptyText(leaves, noHud);
        assertNull(leaves.get(WorldFormLeaves.ZONE_PREFIX_LEAF), "no ZoneHud group authored: inherit");
        assertNull(leaves.get(WorldFormLeaves.BIOME_PREFIX_LEAF));
    }

    @Test
    void aFieldTheFormDidNotCollectIsNotInvented() throws Exception {
        WorldSettings file = authored("{ \"ZoneHud\": { \"ZoneNameKeyPrefix\": \"\" } }");
        Map<String, Object> leaves = new LinkedHashMap<>();
        leaves.put("Difficulty.Floor", 1.0);
        WorldFormLeaves.keepAuthoredEmptyText(leaves, file);
        assertFalse(leaves.containsKey(WorldFormLeaves.ZONE_PREFIX_LEAF),
                "only a leaf the form collected as blank is reconciled");
    }
}
