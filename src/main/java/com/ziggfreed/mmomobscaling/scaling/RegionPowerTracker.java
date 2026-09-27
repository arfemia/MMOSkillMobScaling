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
 * <p><b>How a world folds is DECLARED per world, by the presence tick, which holds the per-world
 * settings view</b> ({@link #adoptWorldFold}): the grid size its keys are composed with and the
 * {@link AggregationMode} its buckets fold under. Every bucket in a world folds under that world's
 * declared mode, on a presence update and on a removal alike, so a removal never has to resolve a
 * world's settings itself (the entity-removal hook holds no world) and a world authoring
 * {@code PEAK} genuinely folds to its strongest member while its neighbour authoring {@code AVERAGE}
 * folds to the mean. A declared mode CHANGE refolds the world's buckets in place; a declared grid
 * size change PURGES the world's tracked presence, since every key composed under the old size is
 * stale and the next tick re-registers everyone under the new one. A world nobody declared folds
 * {@code AVERAGE}, the shipped default.
 *
 * <p><b>A world declared {@code DISABLED}, or one no presence tick has declared yet, has NO OPINION
 * about power, which is not the same as zero</b> ({@link #holdsOpinion}). A {@code DISABLED} world's
 * buckets still track who is where (so a live switch to another mode refolds in place), but
 * {@link #scalarIfTracked} answers {@code null} there and {@link #readingFor} answers {@code null} too:
 * the boss framework's power seam and the {@code region_power} factor both read ABSENT and fall back
 * to their own posture, the way they do for a world this mod never tracked. An UNDECLARED world is
 * indistinguishable from that cold miss on purpose: until its tick has said how it folds, the tracker
 * cannot know whether the world is {@code DISABLED}, so a confident {@code 0.0} there would be exactly
 * the wrong answer the absent read exists to prevent. Only the spawn path's zero-delta read
 * ({@link #scalarFor}) answers {@code 0.0}, because for a spawn "no opinion" and "no delta" are the
 * same thing.
 *
 * <p>Thread-safe: presences, buckets and world folds are {@code ConcurrentHashMap}s, per-bucket
 * membership mutates under the bucket's monitor, and the folded scalar is a volatile read. Pure logic
 * + ziggfreed-common's {@link PowerAggregation} only - no engine types, freely unit-testable.
 */
public final class RegionPowerTracker {

    private static final RegionPowerTracker INSTANCE = new RegionPowerTracker();

    /** The fold a world nobody declared uses: the shipped {@code OpenWorld.AggregationMode} default. */
    private static final AggregationMode UNDECLARED_MODE = AggregationMode.AVERAGE;

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

    /** One world's declared fold: the grid size its keys use and the mode its buckets fold under. */
    private record WorldFold(int regionSizeChunks, @Nonnull AggregationMode mode) {
    }

    /** One region's members + the cached fold of their powers (recomputed on membership change). */
    private static final class Bucket {
        private final Map<UUID, Double> powers = new HashMap<>();
        private volatile double scalar;
    }

    private final ConcurrentHashMap<UUID, Presence> presences = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<RegionKey, Bucket>> worlds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WorldFold> worldFolds = new ConcurrentHashMap<>();

    private RegionPowerTracker() {
    }

    /** Pack the sub-grid cell coords of a chunk into one long. {@code regionSizeChunks} is clamped to >= 1. */
    public static long gridKey(int chunkX, int chunkZ, int regionSizeChunks) {
        int size = Math.max(1, regionSizeChunks);
        long rx = Math.floorDiv(chunkX, size);
        long rz = Math.floorDiv(chunkZ, size);
        return (rx << 32) | (rz & 0xFFFFFFFFL);
    }

    /**
     * Declare how {@code worldKey} folds: the grid size its region keys are composed with and the
     * mode its buckets fold under. Called by the presence tick before every {@link #isCurrent} read,
     * with the per-world settings view in hand; on the steady state it is one map read and two
     * compares. A change of MODE refolds every bucket in the world under the new one, in place. A
     * change of GRID SIZE purges the world's tracked presence ({@link #clearWorld}), because every key
     * composed under the old size is stale; the next tick re-registers each player under the new one.
     *
     * @return true when the grid size changed and the world's presence was purged
     */
    public boolean adoptWorldFold(@Nonnull String worldKey, int regionSizeChunks, @Nonnull AggregationMode mode) {
        int size = Math.max(1, regionSizeChunks);
        WorldFold prev = worldFolds.get(worldKey);
        if (prev != null && prev.regionSizeChunks() == size && prev.mode() == mode) {
            return false; // steady state: the declaration stands
        }
        worldFolds.put(worldKey, new WorldFold(size, mode));
        if (prev != null && prev.regionSizeChunks() != size) {
            clearWorld(worldKey);
            return true;
        }
        refoldWorld(worldKey, mode); // a no-op for a world with no buckets yet
        return false;
    }

    /** True when the player is already tracked in exactly this world+region (the per-tick hot path). */
    public boolean isCurrent(@Nonnull UUID playerId, @Nonnull String worldKey, @Nonnull RegionKey regionKey) {
        Presence p = presences.get(playerId);
        return p != null && p.regionKey().equals(regionKey) && p.worldKey().equals(worldKey);
    }

    /**
     * Move a player's tracked presence to {@code worldKey}/{@code regionKey} at {@code power},
     * removing them from their previous region (if any) and re-folding both buckets, each under its
     * own world's declared mode. Call ONLY on a cross ({@link #isCurrent} false) or to refresh power.
     */
    public void updatePresence(@Nonnull UUID playerId, @Nonnull String worldKey, @Nonnull RegionKey regionKey,
            double power) {
        Presence prev = presences.put(playerId, new Presence(worldKey, regionKey, power));
        if (prev != null) {
            removeFromBucket(prev.worldKey(), prev.regionKey(), playerId);
        }
        Bucket bucket = worlds.computeIfAbsent(worldKey, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(regionKey, k -> new Bucket());
        synchronized (bucket) {
            bucket.powers.put(playerId, power);
            refold(bucket, modeOf(worldKey));
        }
    }

    /** Drop a player's tracked presence entirely (disconnect / entity removal / world unload). */
    public void removePresence(@Nonnull UUID playerId) {
        Presence prev = presences.remove(playerId);
        if (prev != null) {
            removeFromBucket(prev.worldKey(), prev.regionKey(), playerId);
        }
    }

    /**
     * The cached aggregated power of the players in this world+region; {@code 0.0} when none are
     * tracked (the cold-miss zero delta) and {@code 0.0} in a {@code DISABLED} world (no delta either
     * way). The spawn path's read. O(1) - two map reads + a volatile read.
     */
    public double scalarFor(@Nonnull String worldKey, @Nonnull RegionKey regionKey) {
        if (isDisabled(worldKey)) {
            return 0.0;
        }
        Bucket bucket = bucketOf(worldKey, regionKey);
        return bucket == null ? 0.0 : bucket.scalar;
    }

    /**
     * The cached aggregated power of the players in this world+region, or {@code null} when this mod
     * has nothing to say: NO player is tracked there, the world declared {@code DISABLED} (it tracks
     * presence but holds no opinion about power), or no tick has declared the world at all. The read
     * for a caller that must tell "unknown" apart from "the people here fold to zero" - the boss
     * framework's power seam. An empty bucket is dropped on its last member's removal, so a present
     * bucket always holds at least one tracked player. O(1), like {@link #scalarFor}.
     */
    @Nullable
    public Double scalarIfTracked(@Nonnull String worldKey, @Nonnull RegionKey regionKey) {
        if (!holdsOpinion(worldKey)) {
            return null;
        }
        Bucket bucket = bucketOf(worldKey, regionKey);
        return bucket == null ? null : bucket.scalar;
    }

    /** The live bucket for this world+region, or {@code null} when nobody is tracked there. */
    @Nullable
    private Bucket bucketOf(@Nonnull String worldKey, @Nonnull RegionKey regionKey) {
        ConcurrentHashMap<RegionKey, Bucket> regions = worlds.get(worldKey);
        return regions == null ? null : regions.get(regionKey);
    }

    /**
     * The factor reading for this world+region: {@code null} in a {@code DISABLED} or undeclared world
     * (this mod has no opinion, so a formula term adds nothing and a gate stays shut), else
     * {@link #scalarFor} - a genuine {@code 0.0} for a cold region, the fold otherwise. The
     * {@code region_power} factor's read.
     */
    @Nullable
    public Double readingFor(@Nonnull String worldKey, @Nonnull RegionKey regionKey) {
        return holdsOpinion(worldKey) ? Double.valueOf(scalarFor(worldKey, regionKey)) : null;
    }

    /** True when {@code worldKey} declared {@code DISABLED}: it holds no opinion about power. */
    public boolean isDisabled(@Nonnull String worldKey) {
        return modeOf(worldKey) == AggregationMode.DISABLED;
    }

    /**
     * True when this tracker can vouch for a power reading in {@code worldKey}: a presence tick has
     * declared how the world folds ({@link #adoptWorldFold}) and it is not {@code DISABLED}. False for
     * an undeclared world, which the absent-answering reads treat exactly like a cold miss.
     */
    public boolean holdsOpinion(@Nonnull String worldKey) {
        WorldFold fold = worldFolds.get(worldKey);
        return fold != null && fold.mode() != AggregationMode.DISABLED;
    }

    /** Tracked-player count (diagnostics / tests). */
    public int trackedPlayers() {
        return presences.size();
    }

    /**
     * Drop every tracked presence and bucket of ONE world, keeping its declared fold. The next
     * presence tick in that world finds nobody current and re-registers each player under the
     * current grid size. Other worlds are untouched.
     */
    public void clearWorld(@Nonnull String worldKey) {
        presences.values().removeIf(p -> p.worldKey().equals(worldKey));
        worlds.remove(worldKey);
    }

    /** Drop ALL tracked state, declared folds included (tests / a full reload). */
    public void clearAll() {
        presences.clear();
        worlds.clear();
        worldFolds.clear();
    }

    /** The mode {@code worldKey}'s buckets fold under: its declared one, else the shipped default. */
    @Nonnull
    private AggregationMode modeOf(@Nonnull String worldKey) {
        WorldFold fold = worldFolds.get(worldKey);
        return fold == null ? UNDECLARED_MODE : fold.mode();
    }

    private void removeFromBucket(@Nonnull String worldKey, @Nonnull RegionKey regionKey, @Nonnull UUID playerId) {
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
            refold(bucket, modeOf(worldKey));
        }
    }

    /** Refold every bucket of one world under {@code mode} (a declared mode change, membership unchanged). */
    private void refoldWorld(@Nonnull String worldKey, @Nonnull AggregationMode mode) {
        ConcurrentHashMap<RegionKey, Bucket> regions = worlds.get(worldKey);
        if (regions == null) {
            return;
        }
        for (Bucket bucket : regions.values()) {
            synchronized (bucket) {
                refold(bucket, mode);
            }
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
