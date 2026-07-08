package com.ziggfreed.mmomobscaling.world;

import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.spawn.ISpawnProvider;
import com.hypixel.hytale.server.worldgen.chunk.ChunkGenerator;
import com.hypixel.hytale.server.worldgen.chunk.ZoneBiomeResult;
import com.ziggfreed.mmomobscaling.config.DifficultyConfig;
import com.ziggfreed.mmomobscaling.config.SpawnScalingSettings;

/**
 * The layered difficulty-FLOOR resolver over NATIVE worldgen primitives - it never invents a zone
 * registry. Per chunk it resolves, in precedence order: authored {@code DifficultyMapping} for the
 * native ZONE ({@code Zone.name()}, exact then segment PREFIX) &gt; for the native BIOME
 * ({@code Biome.getName()}, exact then segment PREFIX) &gt; the zone wildcard {@code *} &gt; the biome
 * wildcard {@code *} &gt; the WORLD-BASELINE floor ({@code SpawnScalingSettings.getDifficultyFloor()},
 * 1.0.2: the per-world {@code Difficulty.Floor} or the global default; so a named biome floor beats
 * the zone wildcard). On top of that base an optional DISTANCE ESCALATION adds
 * {@code (distFromSpawn - start) / blocksPerPoint} difficulty (capped at {@code MaxBonus}) and raises
 * the rarity spawn chance by {@code RarityChancePerPoint} per point - so far enough from spawn EVERY
 * zone is deadly, configurable in {@code Difficulty.DistanceEscalation}.
 *
 * <p><b>Memoization:</b> the native zone/biome NAMES are immutable for a given (world seed, chunk), so
 * they are memoized per world per chunk (one {@code getZoneBiomeResultAt} ever per chunk; the engine
 * LRU-caches the underlying query too). Floors/escalation are recomputed per read from the live
 * configs (cheap: two O(1) index reads + arithmetic), so a mapping/rules/settings reload needs NO memo
 * invalidation. The memo is size-bounded (a runaway exploration clears it; it simply re-fills).
 *
 * <p>A world whose generator is not the native {@link ChunkGenerator} (flat/void/custom) has no
 * zone/biome data: {@link ChunkInfo#zoneName()} is {@code ""} (the region tracker falls back to the
 * pure chunk grid) and the floor falls to the world baseline (+ escalation, which still applies).
 *
 * <p>Thread-safety: all state is {@link ConcurrentHashMap}s + immutable records; called from spawn
 * hooks, presence ticks, HUD ticks and commands on world threads.
 */
public final class ZoneDifficultyResolver {

    /** Per-world chunk-memo bound; on overflow the memo clears and re-fills (never unbounded). */
    private static final int MEMO_MAX_CHUNKS = 65_536;

    private static final ZoneDifficultyResolver INSTANCE = new ZoneDifficultyResolver();

    /** The no-zone-data marker ({@code zoneName}/{@code biomeName} when the world has no native worldgen). */
    public static final String NO_ZONE = "";

    @Nonnull
    public static ZoneDifficultyResolver get() {
        return INSTANCE;
    }

    private final ConcurrentHashMap<String, WorldMemo> worlds = new ConcurrentHashMap<>();

    private ZoneDifficultyResolver() {
    }

    /** The memoized native zone/biome names for a chunk ({@link #NO_ZONE} fields when unavailable). */
    public record ChunkInfo(@Nonnull String zoneName, @Nonnull String biomeName) {
        static final ChunkInfo NONE = new ChunkInfo(NO_ZONE, NO_ZONE);
    }

    /**
     * One fully-resolved floor read (the {@code /mobscaling inspect} breakdown): the native zone name,
     * the pre-escalation base floor, the additive distance bonus, the cap-clamped effective floor the
     * group delta rides on, the escalation-boosted rarity spawn chance, whether the spawn is INSIDE the
     * start ring (where player-power scaling is fully off), the raw distance from world spawn, and the
     * native biome name.
     */
    public record ResolvedFloor(@Nonnull String zoneName, double baseFloor, double escalationBonus,
            double effectiveFloor, double raritySpawnChance, boolean insideStartRing,
            double distanceFromSpawn, @Nonnull String biomeName) {
    }

    /**
     * The memoized zone/biome names for a chunk; ONE {@code getZoneBiomeResultAt} per chunk ever
     * (evaluated at the chunk-centre block).
     */
    @Nonnull
    public ChunkInfo chunkInfo(@Nonnull World world, int chunkX, int chunkZ) {
        WorldMemo memo = memoFor(world);
        if (!memo.hasZones) {
            return ChunkInfo.NONE;
        }
        long key = (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
        ChunkInfo cached = memo.chunks.get(key);
        if (cached != null) {
            return cached;
        }
        ChunkInfo info = queryChunk(world, chunkX, chunkZ, memo.seed);
        if (memo.chunks.size() >= MEMO_MAX_CHUNKS) {
            memo.chunks.clear(); // crude but bounded; the memo simply re-fills
        }
        memo.chunks.put(key, info);
        return info;
    }

    /** The region-bucket zone namespace for a chunk: the native zone name, or {@link #NO_ZONE} (grid fallback). */
    @Nonnull
    public String zoneKey(@Nonnull World world, int chunkX, int chunkZ) {
        return chunkInfo(world, chunkX, chunkZ).zoneName();
    }

    /**
     * Resolve the full floor breakdown for a chunk: mapping-layer base (zone &gt; biome &gt; world
     * baseline), plus the distance escalation, clamped to the configured caps. Floors are read LIVE
     * from {@link DifficultyConfig}/{@link SpawnScalingSettings} (only the immutable zone/biome names
     * are memoized), so config reloads take effect without invalidation.
     *
     * <p>{@code settings} is the spawn-time settings surface: the GLOBAL config, or a per-world
     * overlay view for a world matched by a {@code Worlds/*.json} rule (1.0.2) - so a dungeon's
     * authored floor / caps / escalation toggle apply here without a special case.
     */
    @Nonnull
    public ResolvedFloor resolve(@Nonnull World world, int chunkX, int chunkZ,
            @Nonnull SpawnScalingSettings settings) {
        ChunkInfo info = chunkInfo(world, chunkX, chunkZ);
        double base = baseFloor(info, settings);
        // Distance from world spawn is resolved UNCONDITIONALLY (even with escalation off): the start
        // ring is the "power scaling off near spawn" gate, independent of the additive escalation bonus.
        WorldMemo memo = memoFor(world);
        double dx = centerBlock(chunkX) - memo.spawnX;
        double dz = centerBlock(chunkZ) - memo.spawnZ;
        double distance = Math.sqrt(dx * dx + dz * dz);
        double startDistance = settings.getEscalationStartDistanceBlocks();
        boolean insideStartRing = startDistance > 0.0 && distance <= startDistance;
        double bonus = 0.0;
        if (settings.isDistanceEscalationEnabled()) {
            bonus = escalationBonus(distance, startDistance,
                    settings.getEscalationBlocksPerPoint(), settings.getEscalationMaxBonus());
        }
        double effective = clamp(base + bonus, settings.getDifficultyMinCap(), settings.getDifficultyMaxCap());
        double chance = clamp(settings.getRaritySpawnChance()
                + bonus * settings.getEscalationRarityChancePerPoint(), 0.0, 1.0);
        return new ResolvedFloor(info.zoneName(), base, bonus, effective, chance,
                insideStartRing, distance, info.biomeName());
    }

    /**
     * The mapping-layer base floor, precedence: zone-EXACT &gt; zone-PREFIX &gt; biome-EXACT &gt;
     * biome-PREFIX &gt; zone-WILDCARD({@code *}) &gt; biome-WILDCARD({@code *}) &gt; the world-baseline
     * {@code SpawnScalingSettings.getDifficultyFloor()} (per-world or global, 1.0.2). A named biome
     * floor (e.g. {@code OceanBiome}) therefore beats the {@code ZoneAny} wildcard, which previously
     * shadowed it. The two wildcards apply only where the chunk actually has that layer's data (a
     * zoneless chunk never matches the zone wildcard).
     */
    static double baseFloor(@Nonnull ChunkInfo info, @Nonnull SpawnScalingSettings settings) {
        DifficultyConfig mappings = DifficultyConfig.getInstance();
        boolean hasZone = !info.zoneName().isEmpty();
        boolean hasBiome = !info.biomeName().isEmpty();
        if (hasZone) {
            Double zoneSpecific = mappings.zoneFloorSpecific(info.zoneName());
            if (zoneSpecific != null) {
                return zoneSpecific;
            }
        }
        if (hasBiome) {
            Double biomeSpecific = mappings.biomeFloorSpecific(info.biomeName());
            if (biomeSpecific != null) {
                return biomeSpecific;
            }
        }
        if (hasZone) {
            Double zoneWildcard = mappings.zoneWildcard();
            if (zoneWildcard != null) {
                return zoneWildcard;
            }
        }
        if (hasBiome) {
            Double biomeWildcard = mappings.biomeWildcard();
            if (biomeWildcard != null) {
                return biomeWildcard;
            }
        }
        return settings.getDifficultyFloor();
    }

    /** The additive distance-escalation bonus (pure; unit-tested): 0 inside the start radius, then linear, capped. */
    static double escalationBonus(double distance, double startDistance, double blocksPerPoint, double maxBonus) {
        if (distance <= startDistance || blocksPerPoint <= 0.0 || maxBonus <= 0.0) {
            return 0.0;
        }
        return Math.min(maxBonus, (distance - startDistance) / blocksPerPoint);
    }

    /** Drop ALL memoized state (tests / a full reload). */
    public void clearAll() {
        worlds.clear();
    }

    /** Drop one world's memo (world unload). */
    public void onWorldRemoved(@Nonnull String worldName) {
        worlds.remove(worldName);
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    /** One world's immutable memo context: zone capability, seed, spawn anchor, chunk-name memo. */
    private static final class WorldMemo {
        final boolean hasZones;
        final int seed;
        final double spawnX;
        final double spawnZ;
        final ConcurrentHashMap<Long, ChunkInfo> chunks = new ConcurrentHashMap<>();

        WorldMemo(boolean hasZones, int seed, double spawnX, double spawnZ) {
            this.hasZones = hasZones;
            this.seed = seed;
            this.spawnX = spawnX;
            this.spawnZ = spawnZ;
        }
    }

    @Nonnull
    private WorldMemo memoFor(@Nonnull World world) {
        return worlds.computeIfAbsent(world.getName(), k -> buildMemo(world));
    }

    @Nonnull
    private static WorldMemo buildMemo(@Nonnull World world) {
        boolean hasZones = false;
        int seed = 0;
        double spawnX = 0.0;
        double spawnZ = 0.0;
        try {
            hasZones = world.getChunkStore().getGenerator() instanceof ChunkGenerator;
            seed = (int) world.getWorldConfig().getSeed();
            ISpawnProvider spawnProvider = world.getWorldConfig().getSpawnProvider();
            if (spawnProvider != null) {
                Transform[] points = spawnProvider.getSpawnPoints();
                if (points != null && points.length > 0 && points[0] != null) {
                    spawnX = points[0].getPosition().x();
                    spawnZ = points[0].getPosition().z();
                }
            }
        } catch (Throwable t) {
            // A broken/unusual world config degrades to the no-zone, spawn-at-origin baseline.
        }
        return new WorldMemo(hasZones, seed, spawnX, spawnZ);
    }

    /** The zone/biome names at the chunk-centre block; {@link ChunkInfo#NONE} on any engine hiccup. */
    @Nonnull
    private static ChunkInfo queryChunk(@Nonnull World world, int chunkX, int chunkZ, int seed) {
        try {
            if (!(world.getChunkStore().getGenerator() instanceof ChunkGenerator generator)) {
                return ChunkInfo.NONE;
            }
            ZoneBiomeResult result = generator.getZoneBiomeResultAt(seed, centerBlock(chunkX), centerBlock(chunkZ));
            if (result == null) {
                return ChunkInfo.NONE;
            }
            String zone = result.getZoneResult() != null && result.getZoneResult().getZone() != null
                    ? result.getZoneResult().getZone().name() : null;
            String biome = result.getBiome() != null ? result.getBiome().getName() : null;
            return new ChunkInfo(zone != null ? zone : NO_ZONE, biome != null ? biome : NO_ZONE);
        } catch (Throwable t) {
            return ChunkInfo.NONE; // treat a worldgen hiccup as no-zone; the world baseline stands
        }
    }

    /** The centre block coordinate of a chunk axis (chunks are {@code ChunkUtil.SIZE} = 32 blocks). */
    private static int centerBlock(int chunkCoord) {
        return (chunkCoord << ChunkUtil.BITS) + (ChunkUtil.SIZE / 2);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
