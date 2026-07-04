package com.ziggfreed.mmomobscaling.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.mmoskilltree.world.WorldRules;
import com.ziggfreed.mmomobscaling.config.DifficultyConfig;
import com.ziggfreed.mmomobscaling.world.ZoneDifficultyResolver.ChunkInfo;

/**
 * Exercises the PURE halves of the zone floor resolver: the mapping-layer precedence
 * (zone-exact &gt; zone-PREFIX &gt; biome-exact &gt; biome-PREFIX &gt; zone {@code *} &gt; biome
 * {@code *} &gt; world baseline, with segment-boundary prefix matching + longest-prefix-wins) and the
 * distance-escalation math (start radius, linear slope, cap, degenerate inputs). The engine-facing
 * memo (ChunkGenerator/spawn reads) is exercised in-game; these tests pin the resolution semantics.
 */
class ZoneDifficultyResolverTest {

    /** A rules instance whose only relevant field is the mob-difficulty floor baseline (30). */
    private static final WorldRules RULES =
            new WorldRules(1.0, true, true, Set.of(), Set.of(), 30.0, true);

    @AfterEach
    void reset() {
        DifficultyConfig.getInstance().mergePackLayer(Map.of());
        DifficultyConfig.getInstance().mergeOwnerLayer(Map.of());
    }

    private static DifficultyMapping zone(String id, String target, double floor) {
        return new DifficultyMapping(id, DifficultyMapping.TargetType.ZONE, target, floor);
    }

    private static DifficultyMapping biome(String id, String target, double floor) {
        return new DifficultyMapping(id, DifficultyMapping.TargetType.BIOME, target, floor);
    }

    @Test
    void zoneExactBeatsEverything() {
        DifficultyConfig.getInstance().mergePackLayer(Map.of(
                "zone2", zone("zone2", "Zone2", 22.0),
                "zoneany", zone("zoneany", "*", 10.0),
                "ocean", biome("ocean", "Ocean1", 12.0)));
        assertEquals(22.0, ZoneDifficultyResolver.baseFloor(new ChunkInfo("Zone2", "Ocean1"), RULES), 1e-9,
                "the exact zone mapping wins over wildcard and biome");
    }

    @Test
    void namedBiomeBeatsZoneWildcard() {
        DifficultyConfig.getInstance().mergePackLayer(Map.of(
                "zoneany", zone("zoneany", "*", 10.0),
                "ocean", biome("ocean", "Ocean1", 12.0)));
        assertEquals(12.0, ZoneDifficultyResolver.baseFloor(new ChunkInfo("Zone9", "Ocean1"), RULES), 1e-9,
                "a named biome floor beats the zone wildcard (biome-specific outranks zone-*)");
    }

    @Test
    void zoneWildcardBeatsBiomeWildcard() {
        DifficultyConfig.getInstance().mergePackLayer(Map.of(
                "zoneany", zone("zoneany", "*", 10.0),
                "biomeany", biome("biomeany", "*", 5.0)));
        assertEquals(10.0, ZoneDifficultyResolver.baseFloor(new ChunkInfo("Zone9", "Plains9"), RULES), 1e-9,
                "with only wildcards, the zone wildcard outranks the biome wildcard");
    }

    @Test
    void zonePrefixMatchesCompoundNameAndExactOverrideWins() {
        DifficultyConfig.getInstance().mergePackLayer(Map.of(
                "zone2", zone("zone2", "Zone2", 22.0),
                "zone2tier1", zone("zone2tier1", "Zone2_Tier1", 18.0)));
        assertEquals(22.0, ZoneDifficultyResolver.baseFloor(new ChunkInfo("Zone2_Shore", ""), RULES), 1e-9,
                "Zone2 prefix-matches the compound Zone2_Shore sub-zone");
        assertEquals(18.0, ZoneDifficultyResolver.baseFloor(new ChunkInfo("Zone2_Tier1", ""), RULES), 1e-9,
                "the longer exact override wins over the prefix family");
    }

    @Test
    void prefixMatchStopsAtSegmentBoundary() {
        DifficultyConfig.getInstance().mergePackLayer(Map.of(
                "zone1", zone("zone1", "Zone1", 8.0),
                "zoneany", zone("zoneany", "*", 10.0)));
        assertEquals(10.0, ZoneDifficultyResolver.baseFloor(new ChunkInfo("Zone10_Spawn", ""), RULES), 1e-9,
                "Zone1 must NOT prefix-match Zone10_Spawn (the trailing '_' guards the boundary)");
        assertEquals(8.0, ZoneDifficultyResolver.baseFloor(new ChunkInfo("Zone1_Spawn", ""), RULES), 1e-9,
                "Zone1 does prefix-match its own Zone1_Spawn segment");
    }

    @Test
    void longestPrefixWins() {
        DifficultyConfig.getInstance().mergePackLayer(Map.of(
                "zone3", zone("zone3", "Zone3", 38.0),
                "zone3shore", zone("zone3shore", "Zone3_Shore", 30.0)));
        assertEquals(30.0, ZoneDifficultyResolver.baseFloor(new ChunkInfo("Zone3_Shore_Tier1", ""), RULES), 1e-9,
                "the longer Zone3_Shore prefix wins over the shorter Zone3 prefix");
    }

    @Test
    void biomeLayerFiresWhenNoZoneMappingMatches() {
        DifficultyConfig.getInstance().mergePackLayer(Map.of(
                "ocean", biome("ocean", "Ocean1", 12.0)));
        assertEquals(12.0, ZoneDifficultyResolver.baseFloor(new ChunkInfo("Zone9", "Ocean1"), RULES), 1e-9,
                "no zone mapping at all: the exact biome mapping fires");
        assertEquals(30.0, ZoneDifficultyResolver.baseFloor(new ChunkInfo("Zone9", "Plains1"), RULES), 1e-9,
                "no biome match either: the WorldRules baseline stands");
    }

    @Test
    void zonelessChunkSkipsToTheBiomeThenBaseline() {
        DifficultyConfig.getInstance().mergePackLayer(Map.of(
                "zoneany", zone("zoneany", "*", 10.0)));
        assertEquals(30.0,
                ZoneDifficultyResolver.baseFloor(new ChunkInfo(ZoneDifficultyResolver.NO_ZONE,
                        ZoneDifficultyResolver.NO_ZONE), RULES), 1e-9,
                "a no-zone-data chunk never matches the zone wildcard; the baseline stands");
    }

    @Test
    void matchingIsCaseInsensitive() {
        DifficultyConfig.getInstance().mergePackLayer(Map.of(
                "zone2", zone("zone2", "ZONE2", 22.0)));
        assertEquals(22.0, ZoneDifficultyResolver.baseFloor(new ChunkInfo("zone2", ""), RULES), 1e-9,
                "authored casing never splits a mapping from the native name");
    }

    @Test
    void escalationIsZeroInsideTheStartRadius() {
        assertEquals(0.0, ZoneDifficultyResolver.escalationBonus(4999.0, 5000.0, 500.0, 92.0), 1e-9);
        assertEquals(0.0, ZoneDifficultyResolver.escalationBonus(5000.0, 5000.0, 500.0, 92.0), 1e-9);
    }

    @Test
    void escalationClimbsLinearlyThenCaps() {
        assertEquals(1.0, ZoneDifficultyResolver.escalationBonus(5500.0, 5000.0, 500.0, 92.0), 1e-9,
                "+1 per BlocksPerPoint past the start radius");
        assertEquals(20.0, ZoneDifficultyResolver.escalationBonus(15000.0, 5000.0, 500.0, 92.0), 1e-9);
        assertEquals(92.0, ZoneDifficultyResolver.escalationBonus(1_000_000.0, 5000.0, 500.0, 92.0), 1e-9,
                "a million blocks out saturates at MaxBonus (the frontier is deadly, not infinite)");
    }

    @Test
    void degenerateEscalationInputsAreSafe() {
        assertEquals(0.0, ZoneDifficultyResolver.escalationBonus(9999.0, 5000.0, 0.0, 92.0), 1e-9,
                "a zero slope never divides by zero");
        assertEquals(0.0, ZoneDifficultyResolver.escalationBonus(9999.0, 5000.0, 500.0, 0.0), 1e-9,
                "a zero cap disables the bonus");
    }

    @Test
    void difficultyConfigIndexResolvesExactThenWildcard() {
        DifficultyConfig cfg = DifficultyConfig.getInstance();
        cfg.mergePackLayer(Map.of(
                "zone2", zone("zone2", "Zone2", 22.0),
                "zoneany", zone("zoneany", "*", 10.0)));
        assertEquals(22.0, cfg.zoneFloor("Zone2"), 1e-9, "exact");
        assertEquals(10.0, cfg.zoneFloor("Zone7"), 1e-9, "wildcard");
        assertNull(cfg.biomeFloor("Ocean1"), "no biome mappings loaded");
        // An owner overlay re-points one mapping; the derived index rebuilds.
        cfg.mergeOwnerLayer(Map.of("zone2", zone("zone2", "Zone2", 60.0)));
        assertEquals(60.0, cfg.zoneFloor("Zone2"), 1e-9, "owner layer wins and the index rebuilt");
    }

    @Test
    void difficultyConfigSplitsSpecificFromWildcard() {
        DifficultyConfig cfg = DifficultyConfig.getInstance();
        cfg.mergePackLayer(Map.of(
                "zone2", zone("zone2", "Zone2", 22.0),
                "zoneany", zone("zoneany", "*", 10.0)));
        assertEquals(22.0, cfg.zoneFloorSpecific("Zone2_Shore"), 1e-9,
                "specific query prefix-matches the compound name");
        assertNull(cfg.zoneFloorSpecific("Zone7"), "specific query never falls back to the wildcard");
        assertEquals(10.0, cfg.zoneWildcard(), 1e-9, "wildcard exposed separately");
        assertNull(cfg.biomeWildcard(), "no biome wildcard authored");
    }
}
