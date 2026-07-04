package com.ziggfreed.mmomobscaling.scaling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.scaling.AggregationMode;
import com.ziggfreed.mmomobscaling.scaling.RegionPowerTracker.RegionKey;

/**
 * Exercises the cached per-region power aggregate: cross bookkeeping, O(1) scalar reads, fold modes,
 * removal cleanup, per-world isolation, the sub-grid key math (incl. negative coords), and the
 * zone+proximity hybrid keying (zone splits a shared grid cell; {@code ""} is the zoneless fallback).
 */
class RegionPowerTrackerTest {

    private static final UUID P1 = new UUID(0L, 1L);
    private static final UUID P2 = new UUID(0L, 2L);

    /** The zone+grid bucket key for the default Zone1 test namespace. */
    private static RegionKey zone1(int chunkX, int chunkZ) {
        return new RegionKey("Zone1", RegionPowerTracker.gridKey(chunkX, chunkZ, 3));
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
        RegionPowerTracker t = RegionPowerTracker.get();
        long sameCell = RegionPowerTracker.gridKey(0, 0, 3);
        RegionKey inZone1 = new RegionKey("Zone1", sameCell);
        RegionKey inZone2 = new RegionKey("Zone2", sameCell);
        t.updatePresence(P1, "orbis", inZone1, 40.0, AggregationMode.AVERAGE);
        t.updatePresence(P2, "orbis", inZone2, 90.0, AggregationMode.AVERAGE);
        assertEquals(40.0, t.scalarFor("orbis", inZone1), 1e-9,
                "a zone border splits the bucket even inside one grid cell");
        assertEquals(90.0, t.scalarFor("orbis", inZone2), 1e-9,
                "the neighbouring zone's bucket is independent");
    }

    @Test
    void zonelessFallbackUsesTheEmptyNamespace() {
        RegionPowerTracker t = RegionPowerTracker.get();
        RegionKey gridOnly = new RegionKey("", RegionPowerTracker.gridKey(0, 0, 3));
        t.updatePresence(P1, "flatworld", gridOnly, 40.0, AggregationMode.AVERAGE);
        assertEquals(40.0, t.scalarFor("flatworld", gridOnly), 1e-9,
                "a zoneless world tracks on the pure chunk grid");
    }

    @Test
    void scalarAveragesTrackedPlayers() {
        RegionPowerTracker t = RegionPowerTracker.get();
        RegionKey region = zone1(0, 0);
        t.updatePresence(P1, "orbis", region, 40.0, AggregationMode.AVERAGE);
        t.updatePresence(P2, "orbis", region, 60.0, AggregationMode.AVERAGE);
        assertEquals(50.0, t.scalarFor("orbis", region), 1e-9, "AVERAGE folds to the mean");
        assertEquals(0.0, t.scalarFor("orbis", zone1(30, 30)), 1e-9,
                "a cold region reads 0 (zero delta)");
        assertEquals(0.0, t.scalarFor("otherworld", region), 1e-9, "worlds are isolated");
    }

    @Test
    void crossMovesTheContribution() {
        RegionPowerTracker t = RegionPowerTracker.get();
        RegionKey a = zone1(0, 0);
        RegionKey b = zone1(9, 0);
        t.updatePresence(P1, "orbis", a, 40.0, AggregationMode.AVERAGE);
        assertTrue(t.isCurrent(P1, "orbis", a));
        t.updatePresence(P1, "orbis", b, 45.0, AggregationMode.AVERAGE);
        assertEquals(0.0, t.scalarFor("orbis", a), 1e-9, "the old region bucket empties on cross");
        assertEquals(45.0, t.scalarFor("orbis", b), 1e-9, "the new region carries the refreshed power");
        assertFalse(t.isCurrent(P1, "orbis", a));
        assertTrue(t.isCurrent(P1, "orbis", b));
    }

    @Test
    void removalDropsTheContribution() {
        RegionPowerTracker t = RegionPowerTracker.get();
        RegionKey region = zone1(0, 0);
        t.updatePresence(P1, "orbis", region, 40.0, AggregationMode.AVERAGE);
        t.updatePresence(P2, "orbis", region, 60.0, AggregationMode.AVERAGE);
        t.removePresence(P2, AggregationMode.AVERAGE);
        assertEquals(40.0, t.scalarFor("orbis", region), 1e-9, "the remaining player re-folds");
        t.removePresence(P1, AggregationMode.AVERAGE);
        assertEquals(0.0, t.scalarFor("orbis", region), 1e-9, "an emptied region reads cold");
        assertEquals(0, t.trackedPlayers(), "no ghosts");
    }

    @Test
    void peakModeFoldsToTheMax() {
        RegionPowerTracker t = RegionPowerTracker.get();
        RegionKey region = zone1(0, 0);
        t.updatePresence(P1, "orbis", region, 40.0, AggregationMode.PEAK);
        t.updatePresence(P2, "orbis", region, 60.0, AggregationMode.PEAK);
        assertEquals(60.0, t.scalarFor("orbis", region), 1e-9, "PEAK folds to the strongest");
    }
}
