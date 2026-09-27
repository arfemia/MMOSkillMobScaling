package com.ziggfreed.mmomobscaling.scaling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.scaling.AggregationMode;
import com.ziggfreed.mmomobscaling.scaling.RegionPowerTracker.RegionKey;

/**
 * Exercises the cached per-region power aggregate: cross bookkeeping, O(1) scalar reads, the
 * PER-WORLD declared fold (the mode a world's buckets fold under and the grid size its keys use),
 * removal cleanup, per-world isolation, the sub-grid key math (incl. negative coords), and the
 * zone+proximity hybrid keying (zone splits a shared grid cell; {@code ""} is the zoneless fallback).
 */
class RegionPowerTrackerTest {

    private static final UUID P1 = new UUID(0L, 1L);
    private static final UUID P2 = new UUID(0L, 2L);
    private static final UUID P3 = new UUID(0L, 3L);
    private static final UUID P4 = new UUID(0L, 4L);

    /** The zone+grid bucket key for the default Zone1 test namespace at the shipped 3-chunk grid. */
    private static RegionKey zone1(int chunkX, int chunkZ) {
        return new RegionKey("Zone1", RegionPowerTracker.gridKey(chunkX, chunkZ, 3));
    }

    /** A world declared at the 3-chunk grid under {@code mode}, the way the presence tick declares it. */
    private static RegionPowerTracker world(String worldKey, AggregationMode mode) {
        RegionPowerTracker t = RegionPowerTracker.get();
        t.adoptWorldFold(worldKey, 3, mode);
        return t;
    }

    @AfterEach
    void reset() {
        RegionPowerTracker.get().clearAll();
    }

    @Test
    void gridKeyGridsByFloorDiv() {
        assertEquals(RegionPowerTracker.gridKey(0, 0, 3), RegionPowerTracker.gridKey(2, 2, 3),
                "chunks 0-2 share a 3x3 cell");
        assertFalse(RegionPowerTracker.gridKey(2, 2, 3) == RegionPowerTracker.gridKey(3, 2, 3),
                "chunk 3 starts the next cell");
        assertEquals(RegionPowerTracker.gridKey(-1, -1, 3), RegionPowerTracker.gridKey(-3, -3, 3),
                "negative chunks floor-divide (-3..-1 share a cell)");
        assertFalse(RegionPowerTracker.gridKey(-1, -1, 3) == RegionPowerTracker.gridKey(0, 0, 3),
                "the grid does not straddle zero");
        assertEquals(RegionPowerTracker.gridKey(5, 7, 0), RegionPowerTracker.gridKey(5, 7, 1),
                "a degenerate size clamps to 1");
    }

    @Test
    void zoneSplitsASharedGridCell() {
        RegionPowerTracker t = world("orbis", AggregationMode.AVERAGE);
        long sameCell = RegionPowerTracker.gridKey(0, 0, 3);
        RegionKey inZone1 = new RegionKey("Zone1", sameCell);
        RegionKey inZone2 = new RegionKey("Zone2", sameCell);
        t.updatePresence(P1, "orbis", inZone1, 40.0);
        t.updatePresence(P2, "orbis", inZone2, 90.0);
        assertEquals(40.0, t.scalarFor("orbis", inZone1), 1e-9,
                "a zone border splits the bucket even inside one grid cell");
        assertEquals(90.0, t.scalarFor("orbis", inZone2), 1e-9,
                "the neighbouring zone's bucket is independent");
    }

    @Test
    void zonelessFallbackUsesTheEmptyNamespace() {
        RegionPowerTracker t = world("flatworld", AggregationMode.AVERAGE);
        RegionKey gridOnly = new RegionKey("", RegionPowerTracker.gridKey(0, 0, 3));
        t.updatePresence(P1, "flatworld", gridOnly, 40.0);
        assertEquals(40.0, t.scalarFor("flatworld", gridOnly), 1e-9,
                "a zoneless world tracks on the pure chunk grid");
    }

    @Test
    void scalarIfTrackedTellsAColdRegionFromAZeroFold() {
        RegionPowerTracker t = world("orbis", AggregationMode.AVERAGE);
        RegionKey region = zone1(0, 0);
        assertNull(t.scalarIfTracked("orbis", region), "nobody tracked anywhere in the world reads unknown");
        t.updatePresence(P1, "orbis", region, 0.0);
        assertEquals(0.0, t.scalarIfTracked("orbis", region), 1e-9,
                "a tracked player folding to zero is a real zero, not an unknown");
        assertNull(t.scalarIfTracked("orbis", zone1(30, 30)), "a cold region in a tracked world reads unknown");
        assertNull(t.scalarIfTracked("otherworld", region), "worlds are isolated");
        t.removePresence(P1);
        assertNull(t.scalarIfTracked("orbis", region), "the last member leaving drops the bucket back to unknown");
        assertEquals(0.0, t.scalarFor("orbis", region), 1e-9, "the zero-delta read still answers 0 for the same miss");
    }

    @Test
    void aDisabledWorldHasNoOpinionForTheSeamAndTheFactorButAZeroDeltaForASpawn() {
        // A world authoring OpenWorld.AggregationMode DISABLED still tracks who is where (a live switch
        // to another mode refolds in place) but answers ABSENT to the two consumers that must tell
        // "unknown" from "zero": the boss framework's power seam (scalarIfTracked) and the region_power
        // factor (readingFor). The spawn path's zero-delta read (scalarFor) stays 0.0, since for a spawn
        // "no opinion" and "no delta" are the same thing.
        RegionPowerTracker t = world("quiet", AggregationMode.DISABLED);
        RegionKey region = zone1(0, 0);
        t.updatePresence(P1, "quiet", region, 40.0);
        t.updatePresence(P2, "quiet", region, 60.0);
        assertTrue(t.isDisabled("quiet"));
        assertNull(t.scalarIfTracked("quiet", region), "the power seam reads unknown, never a confident zero");
        assertNull(t.readingFor("quiet", region), "the factor reads absent, so a gate stays shut and a term adds nothing");
        assertEquals(0.0, t.scalarFor("quiet", region), 1e-9, "the spawn read is a zero delta");
        assertEquals(2, t.trackedPlayers(), "presence is still tracked under DISABLED");

        // The same two powers in a tracked world (other players: a presence lives in ONE place): a genuine
        // reading, distinct from the DISABLED absence.
        RegionPowerTracker tracked = world("busy", AggregationMode.AVERAGE);
        tracked.updatePresence(P3, "busy", region, 40.0);
        tracked.updatePresence(P4, "busy", region, 60.0);
        assertEquals(50.0, tracked.scalarIfTracked("busy", region), 1e-9);
        assertEquals(50.0, tracked.readingFor("busy", region), 1e-9);
        assertEquals(0.0, tracked.readingFor("busy", zone1(30, 30)), 1e-9,
                "a cold region in a tracked world is a genuine zero for the factor");
        assertNull(tracked.scalarIfTracked("busy", zone1(30, 30)), "and unknown for the seam");

        // Switching the quiet world back on refolds its buckets into a real reading with nobody moving.
        assertFalse(t.adoptWorldFold("quiet", 3, AggregationMode.PEAK), "a mode change is not a purge");
        assertEquals(60.0, t.scalarIfTracked("quiet", region), 1e-9, "the tracked presence folds under PEAK at once");
        assertEquals(60.0, t.readingFor("quiet", region), 1e-9);
    }

    @Test
    void scalarAveragesTrackedPlayers() {
        RegionPowerTracker t = world("orbis", AggregationMode.AVERAGE);
        RegionKey region = zone1(0, 0);
        t.updatePresence(P1, "orbis", region, 40.0);
        t.updatePresence(P2, "orbis", region, 60.0);
        assertEquals(50.0, t.scalarFor("orbis", region), 1e-9, "AVERAGE folds to the mean");
        assertEquals(0.0, t.scalarFor("orbis", zone1(30, 30)), 1e-9,
                "a cold region reads 0 (zero delta)");
        assertEquals(0.0, t.scalarFor("otherworld", region), 1e-9, "worlds are isolated");
    }

    @Test
    void anUndeclaredWorldFoldsToTheShippedDefaultMeanForASpawnButHoldsNoOpinion() {
        // The presence tick always declares a world before writing into it; a caller that does not
        // (a diagnostic, a test) still gets the shipped AVERAGE on the spawn read rather than a silent
        // zero. The two absent-answering reads, though, cannot vouch for a world nobody declared (it may
        // well be DISABLED), so they answer unknown until the declaration lands.
        RegionPowerTracker t = RegionPowerTracker.get();
        RegionKey region = zone1(0, 0);
        t.updatePresence(P1, "undeclared", region, 40.0);
        t.updatePresence(P2, "undeclared", region, 60.0);
        assertEquals(50.0, t.scalarFor("undeclared", region), 1e-9, "no declaration folds like AVERAGE");
        assertFalse(t.holdsOpinion("undeclared"));
        assertNull(t.scalarIfTracked("undeclared", region), "the power seam reads unknown for an undeclared world");
        assertNull(t.readingFor("undeclared", region), "so does the factor");
        assertFalse(t.adoptWorldFold("undeclared", 3, AggregationMode.AVERAGE), "declaring is not a purge");
        assertTrue(t.holdsOpinion("undeclared"));
        assertEquals(50.0, t.scalarIfTracked("undeclared", region), 1e-9, "once declared, the fold is vouched for");
        assertEquals(50.0, t.readingFor("undeclared", region), 1e-9);
    }

    @Test
    void aDisabledWorldNobodyHasTickedYetIsIndistinguishableFromAColdMiss() {
        // Before any presence tick, a world authoring DISABLED has not told the tracker so. The absent
        // reads must already answer unknown there, never a confident zero, and keep answering unknown
        // once the DISABLED declaration lands; a tracked world's cold region is the only genuine zero.
        RegionPowerTracker t = RegionPowerTracker.get();
        RegionKey region = zone1(0, 0);
        assertNull(t.scalarIfTracked("quiet", region), "nobody declared, nobody tracked: unknown to the seam");
        assertNull(t.readingFor("quiet", region), "and unknown to the factor, not 0.0");
        assertEquals(0.0, t.scalarFor("quiet", region), 1e-9, "the spawn read keeps its zero delta");
        t.adoptWorldFold("quiet", 3, AggregationMode.DISABLED);
        assertNull(t.readingFor("quiet", region), "declared DISABLED: still unknown");
        assertNull(t.scalarIfTracked("quiet", region));
        t.adoptWorldFold("busy", 3, AggregationMode.AVERAGE);
        assertEquals(0.0, t.readingFor("busy", region), 1e-9, "a declared, tracked world's cold region is a real zero");
        assertNull(t.scalarIfTracked("busy", region), "and unknown to the seam, as a cold miss always is");
    }

    @Test
    void crossMovesTheContribution() {
        RegionPowerTracker t = world("orbis", AggregationMode.AVERAGE);
        RegionKey a = zone1(0, 0);
        RegionKey b = zone1(9, 0);
        t.updatePresence(P1, "orbis", a, 40.0);
        assertTrue(t.isCurrent(P1, "orbis", a));
        t.updatePresence(P1, "orbis", b, 45.0);
        assertEquals(0.0, t.scalarFor("orbis", a), 1e-9, "the old region bucket empties on cross");
        assertEquals(45.0, t.scalarFor("orbis", b), 1e-9, "the new region carries the refreshed power");
        assertFalse(t.isCurrent(P1, "orbis", a));
        assertTrue(t.isCurrent(P1, "orbis", b));
    }

    @Test
    void removalDropsTheContribution() {
        RegionPowerTracker t = world("orbis", AggregationMode.AVERAGE);
        RegionKey region = zone1(0, 0);
        t.updatePresence(P1, "orbis", region, 40.0);
        t.updatePresence(P2, "orbis", region, 60.0);
        t.removePresence(P2);
        assertEquals(40.0, t.scalarFor("orbis", region), 1e-9, "the remaining player re-folds");
        t.removePresence(P1);
        assertEquals(0.0, t.scalarFor("orbis", region), 1e-9, "an emptied region reads cold");
        assertEquals(0, t.trackedPlayers(), "no ghosts");
    }

    @Test
    void peakModeFoldsToTheMax() {
        RegionPowerTracker t = world("orbis", AggregationMode.PEAK);
        RegionKey region = zone1(0, 0);
        t.updatePresence(P1, "orbis", region, 40.0);
        t.updatePresence(P2, "orbis", region, 60.0);
        assertEquals(60.0, t.scalarFor("orbis", region), 1e-9, "PEAK folds to the strongest");
    }

    @Test
    void eachWorldFoldsUnderItsOwnDeclaredMode() {
        // The SAME two players at the SAME powers, in a world declared PEAK and then in a world declared
        // AVERAGE: the per-world mode is what the region scalar a spawn reads is folded under, so the two
        // worlds answer different numbers.
        RegionPowerTracker t = RegionPowerTracker.get();
        t.adoptWorldFold("raid", 3, AggregationMode.PEAK);
        t.adoptWorldFold("overworld", 3, AggregationMode.AVERAGE);
        RegionKey region = zone1(0, 0);

        t.updatePresence(P1, "raid", region, 40.0);
        t.updatePresence(P2, "raid", region, 60.0);
        assertEquals(60.0, t.scalarFor("raid", region), 1e-9, "the PEAK world folds to its strongest member");

        t.updatePresence(P1, "overworld", region, 40.0);
        t.updatePresence(P2, "overworld", region, 60.0);
        assertEquals(50.0, t.scalarFor("overworld", region), 1e-9, "the AVERAGE world folds the same pair to the mean");
        assertEquals(0.0, t.scalarFor("raid", region), 1e-9, "the pair left the PEAK world, whose bucket emptied");
    }

    @Test
    void removalRefoldsUnderTheBucketsOwnWorldMode() {
        // The removal hook holds no world, so the bucket it empties must refold under the mode ITS world
        // declared: three players in a PEAK world, the strongest leaves, the fold is still the max.
        RegionPowerTracker t = world("raid", AggregationMode.PEAK);
        RegionKey region = zone1(0, 0);
        t.updatePresence(P1, "raid", region, 40.0);
        t.updatePresence(P2, "raid", region, 60.0);
        t.updatePresence(P3, "raid", region, 90.0);
        t.removePresence(P3);
        assertEquals(60.0, t.scalarFor("raid", region), 1e-9,
                "after the strongest leaves the bucket refolds under PEAK to the next strongest, not the mean");
    }

    @Test
    void aDeclaredModeChangeRefoldsTheWorldInPlace() {
        // A live owner edit of a world's AggregationMode is picked up on the next presence tick's
        // declaration, with nobody having to cross a region border first.
        RegionPowerTracker t = world("orbis", AggregationMode.PEAK);
        RegionKey region = zone1(0, 0);
        t.updatePresence(P1, "orbis", region, 40.0);
        t.updatePresence(P2, "orbis", region, 60.0);
        assertEquals(60.0, t.scalarFor("orbis", region), 1e-9, "declared PEAK");
        assertFalse(t.adoptWorldFold("orbis", 3, AggregationMode.AVERAGE), "a mode change is not a purge");
        assertEquals(50.0, t.scalarFor("orbis", region), 1e-9, "the same bucket now folds to the mean");
        assertEquals(2, t.trackedPlayers(), "membership is untouched by a mode change");
        assertFalse(t.adoptWorldFold("orbis", 3, AggregationMode.AVERAGE), "re-declaring the same fold is a no-op");
    }

    @Test
    void aDeclaredGridSizeChangePurgesThatWorldOnly() {
        // Every region key in a world is composed with that world's grid size, so a size change makes
        // all of them stale: the world's presence is purged (and re-registered by the next tick under the
        // new size) while a neighbouring world keeps its buckets.
        RegionPowerTracker t = RegionPowerTracker.get();
        t.adoptWorldFold("orbis", 3, AggregationMode.AVERAGE);
        t.adoptWorldFold("arena", 3, AggregationMode.AVERAGE);
        RegionKey region = zone1(0, 0);
        t.updatePresence(P1, "orbis", region, 40.0);
        t.updatePresence(P2, "orbis", region, 60.0);
        t.updatePresence(P3, "arena", region, 70.0);

        assertTrue(t.adoptWorldFold("orbis", 5, AggregationMode.AVERAGE), "a grid size change purges the world");
        assertNull(t.scalarIfTracked("orbis", region), "the stale bucket is gone");
        assertFalse(t.isCurrent(P1, "orbis", region), "P1 must re-register under the new size");
        assertFalse(t.isCurrent(P2, "orbis", region), "P2 must re-register under the new size");
        assertEquals(1, t.trackedPlayers(), "only the other world's player is still tracked");
        assertEquals(70.0, t.scalarFor("arena", region), 1e-9, "the neighbouring world is untouched");

        // Re-registering under the new size lands in a fresh bucket that folds under the kept mode.
        RegionKey wide = new RegionKey("Zone1", RegionPowerTracker.gridKey(0, 0, 5));
        t.updatePresence(P1, "orbis", wide, 40.0);
        t.updatePresence(P2, "orbis", wide, 60.0);
        assertEquals(50.0, t.scalarFor("orbis", wide), 1e-9, "the purged world refills under its declared mode");
    }
}
