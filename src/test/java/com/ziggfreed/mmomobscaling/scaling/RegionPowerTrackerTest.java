package com.ziggfreed.mmomobscaling.scaling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.scaling.AggregationMode;

/**
 * Exercises the cached per-region power aggregate: cross bookkeeping, O(1) scalar reads, fold modes,
 * removal cleanup, per-world isolation, and the region-key grid math (incl. negative coords).
 */
class RegionPowerTrackerTest {

    private static final UUID P1 = new UUID(0L, 1L);
    private static final UUID P2 = new UUID(0L, 2L);

    @AfterEach
    void reset() {
        RegionPowerTracker.get().clearAll();
    }

    @Test
    void regionKeyGridsByFloorDiv() {
        assertEquals(RegionPowerTracker.regionKey(0, 0, 3), RegionPowerTracker.regionKey(2, 2, 3),
                "chunks 0-2 share a 3x3 region");
        assertFalse(RegionPowerTracker.regionKey(2, 2, 3) == RegionPowerTracker.regionKey(3, 2, 3),
                "chunk 3 starts the next region");
        assertEquals(RegionPowerTracker.regionKey(-1, -1, 3), RegionPowerTracker.regionKey(-3, -3, 3),
                "negative chunks floor-divide (-3..-1 share a region)");
        assertFalse(RegionPowerTracker.regionKey(-1, -1, 3) == RegionPowerTracker.regionKey(0, 0, 3),
                "the region grid does not straddle zero");
        assertEquals(RegionPowerTracker.regionKey(5, 7, 0), RegionPowerTracker.regionKey(5, 7, 1),
                "a degenerate size clamps to 1");
    }

    @Test
    void scalarAveragesTrackedPlayers() {
        RegionPowerTracker t = RegionPowerTracker.get();
        long region = RegionPowerTracker.regionKey(0, 0, 3);
        t.updatePresence(P1, "orbis", region, 40.0, AggregationMode.AVERAGE);
        t.updatePresence(P2, "orbis", region, 60.0, AggregationMode.AVERAGE);
        assertEquals(50.0, t.scalarFor("orbis", region), 1e-9, "AVERAGE folds to the mean");
        assertEquals(0.0, t.scalarFor("orbis", RegionPowerTracker.regionKey(30, 30, 3)), 1e-9,
                "a cold region reads 0 (zero delta)");
        assertEquals(0.0, t.scalarFor("otherworld", region), 1e-9, "worlds are isolated");
    }

    @Test
    void crossMovesTheContribution() {
        RegionPowerTracker t = RegionPowerTracker.get();
        long a = RegionPowerTracker.regionKey(0, 0, 3);
        long b = RegionPowerTracker.regionKey(9, 0, 3);
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
        long region = RegionPowerTracker.regionKey(0, 0, 3);
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
        long region = RegionPowerTracker.regionKey(0, 0, 3);
        t.updatePresence(P1, "orbis", region, 40.0, AggregationMode.PEAK);
        t.updatePresence(P2, "orbis", region, 60.0, AggregationMode.PEAK);
        assertEquals(60.0, t.scalarFor("orbis", region), 1e-9, "PEAK folds to the strongest");
    }
}
