package com.ziggfreed.mmomobscaling.scaling;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.scaling.AggregationMode;
import com.ziggfreed.common.scaling.PowerAggregation;

/**
 * The CACHED per-region player-power aggregate: the open-world participant source for the group
 * difficulty delta, maintained MOD-SIDE (the binding decision keeps only the per-world floor fields
 * in-jar). {@code MobScalingPresenceSystem} updates a player's presence ONLY on region/world cross
 * (one map read + compare per player per tick otherwise), each bucket re-folds its scalar on
 * mutation, and the spawn hook reads {@link #scalarFor} in O(1) - NEVER a per-spawn player scan.
 * A cold region (no players tracked) reads {@code 0.0} = a zero delta, the authored floor stands.
 *
 * <p><b>Regions are the ZONE + PROXIMITY hybrid:</b> a bucket is keyed per world (by name) by
 * {@link RegionKey} = the NATIVE worldgen zone name ({@code ZoneDifficultyResolver.zoneKey}) plus a
 * {@code regionSizeChunks}-square chunk-grid cell WITHIN it. The zone is the authoritative 1:1
 * namespace (two players in different zones NEVER share a bucket, even in adjacent chunks across a
 * border), while the sub-grid keeps the group delta LOCAL inside a huge zone (a strong player on the
 * far side of Zone2 does not harden your spawns). A world with no native zone data uses
 * {@code zone = ""} - the key degrades to the pure chunk grid (the documented fallback).
 *
 * <p>Thread-safe: presences and buckets are {@code ConcurrentHashMap}s, per-bucket membership mutates
 * under the bucket's monitor, and the folded scalar is a volatile read. Pure logic + ziggfreed-common's
 * {@link PowerAggregation} only - no engine types, freely unit-testable.
 */
public final class RegionPowerTracker {

    private static final RegionPowerTracker INSTANCE = new RegionPowerTracker();

    @Nonnull
    public static RegionPowerTracker get() {
        return INSTANCE;
    }

    /** One bucket key: the native zone namespace + the proximity sub-grid cell within it. */
    public record RegionKey(@Nonnull String zone, long grid) {
    }

    /** One tracked player: which world/region they were last seen in, at what power. */
    private record Presence(@Nonnull String worldKey, @Nonnull RegionKey regionKey, double power) {
    }

    /** One region's members + the cached fold of their powers (recomputed on membership change). */
    private static final class Bucket {
        private final Map<UUID, Double> powers = new HashMap<>();
        private volatile double scalar;
    }

    private final ConcurrentHashMap<UUID, Presence> presences = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<RegionKey, Bucket>> worlds = new ConcurrentHashMap<>();

    private RegionPowerTracker() {
    }

    /** Pack the sub-grid cell coords of a chunk into one long. {@code regionSizeChunks} is clamped to >= 1. */
    public static long gridKey(int chunkX, int chunkZ, int regionSizeChunks) {
        int size = Math.max(1, regionSizeChunks);
        long rx = Math.floorDiv(chunkX, size);
        long rz = Math.floorDiv(chunkZ, size);
        return (rx << 32) | (rz & 0xFFFFFFFFL);
    }

    /** True when the player is already tracked in exactly this world+region (the per-tick hot path). */
    public boolean isCurrent(@Nonnull UUID playerId, @Nonnull String worldKey, @Nonnull RegionKey regionKey) {
        Presence p = presences.get(playerId);
        return p != null && p.regionKey().equals(regionKey) && p.worldKey().equals(worldKey);
    }

    /**
     * Move a player's tracked presence to {@code worldKey}/{@code regionKey} at {@code power},
     * removing them from their previous region (if any) and re-folding both buckets under
     * {@code mode}. Call ONLY on a cross ({@link #isCurrent} false) or to refresh power.
     */
    public void updatePresence(@Nonnull UUID playerId, @Nonnull String worldKey, @Nonnull RegionKey regionKey,
            double power, @Nonnull AggregationMode mode) {
        Presence prev = presences.put(playerId, new Presence(worldKey, regionKey, power));
        if (prev != null) {
            removeFromBucket(prev.worldKey(), prev.regionKey(), playerId, mode);
        }
        Bucket bucket = worlds.computeIfAbsent(worldKey, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(regionKey, k -> new Bucket());
        synchronized (bucket) {
            bucket.powers.put(playerId, power);
            refold(bucket, mode);
        }
    }

    /** Drop a player's tracked presence entirely (disconnect / entity removal / world unload). */
    public void removePresence(@Nonnull UUID playerId, @Nonnull AggregationMode mode) {
        Presence prev = presences.remove(playerId);
        if (prev != null) {
            removeFromBucket(prev.worldKey(), prev.regionKey(), playerId, mode);
        }
    }

    /**
     * The cached aggregated power of the players in this world+region; {@code 0.0} when none are
     * tracked (the cold-miss zero delta). O(1) - two map reads + a volatile read.
     */
    public double scalarFor(@Nonnull String worldKey, @Nonnull RegionKey regionKey) {
        ConcurrentHashMap<RegionKey, Bucket> regions = worlds.get(worldKey);
        if (regions == null) {
            return 0.0;
        }
        Bucket bucket = regions.get(regionKey);
        return bucket == null ? 0.0 : bucket.scalar;
    }

    /** Tracked-player count (diagnostics / tests). */
    public int trackedPlayers() {
        return presences.size();
    }

    /** Drop ALL tracked state (tests / a full reload). */
    public void clearAll() {
        presences.clear();
        worlds.clear();
    }

    private void removeFromBucket(@Nonnull String worldKey, @Nonnull RegionKey regionKey, @Nonnull UUID playerId,
            @Nonnull AggregationMode mode) {
        ConcurrentHashMap<RegionKey, Bucket> regions = worlds.get(worldKey);
        if (regions == null) {
            return;
        }
        Bucket bucket = regions.get(regionKey);
        if (bucket == null) {
            return;
        }
        synchronized (bucket) {
            bucket.powers.remove(playerId);
            if (bucket.powers.isEmpty()) {
                regions.remove(regionKey, bucket); // empty bucket: drop the entry so worlds never grow unbounded
                return;
            }
            refold(bucket, mode);
        }
    }

    /** Recompute the cached scalar from the bucket's members (call while holding the bucket monitor). */
    private static void refold(@Nonnull Bucket bucket, @Nonnull AggregationMode mode) {
        double[] powers = new double[bucket.powers.size()];
        int i = 0;
        for (Double p : bucket.powers.values()) {
            powers[i++] = p != null ? p : 0.0;
        }
        bucket.scalar = PowerAggregation.fold(powers, mode);
    }

    /** Presence lookup for diagnostics ({@code /mobscaling inspect}); {@code null} when untracked. */
    @Nullable
    public String describePresence(@Nonnull UUID playerId) {
        Presence p = presences.get(playerId);
        if (p == null) {
            return null;
        }
        RegionKey key = p.regionKey();
        String zone = key.zone().isEmpty() ? "(no zone)" : key.zone();
        return p.worldKey() + "@" + zone + ":" + (key.grid() >> 32) + "," + (int) key.grid()
                + " power=" + p.power();
    }
}
