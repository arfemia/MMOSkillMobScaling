package com.ziggfreed.mmomobscaling.config;

import javax.annotation.Nonnull;

import com.ziggfreed.mmomobscaling.scaling.MobScaleFold;

/**
 * The spawn-time settings surface a mob-scaling spawn resolves against - the exact getters
 * {@code ZoneDifficultyResolver.resolve} + {@code MobScalingSpawnHook.resolveSpawnScaling} +
 * {@code MobScalingPresenceSystem.mode} read per spawn. {@link MobScalingConfig} implements it (the
 * GLOBAL folded values); {@code MobScalingConfig.spawnSettingsFor(worldName)} returns a per-world
 * OVERLAY view for a world matched by a {@code WorldOverrides} entry (1.0.1), or the config itself
 * when no override matches (zero-alloc common case).
 *
 * <p>This is the ONLY seam the per-world overlay flows through: the resolver + hook take a
 * {@code SpawnScalingSettings} rather than the {@code MobScalingConfig} singleton, so an authored
 * {@code WorldOverride} tunes a dungeon's rarity chance / difficulty caps / stat curve / intensity /
 * player-scaling toggle without touching the global config. Fields NOT here
 * ({@code RegionSizeChunks}, late-arrival / composition) stay global by design - see the router.
 */
public interface SpawnScalingSettings {

    /** Chance a hostile mob rolls a non-plain rarity (before the distance-escalation bonus), clamped [0,1]. */
    double getRaritySpawnChance();

    /** Whether the distance-from-spawn escalation applies in this world. */
    boolean isDistanceEscalationEnabled();

    /** Escalation-free radius around the world spawn (blocks, XZ Euclidean). */
    double getEscalationStartDistanceBlocks();

    /** Blocks per +1 difficulty past the start radius (>= 1). */
    double getEscalationBlocksPerPoint();

    /** Ceiling on the additive distance-escalation difficulty bonus. */
    double getEscalationMaxBonus();

    /** Rarity-spawn-chance bonus per escalation point. */
    double getEscalationRarityChancePerPoint();

    /** Lower clamp on the resolved effective difficulty. */
    double getDifficultyMinCap();

    /** Upper clamp on the resolved effective difficulty. */
    double getDifficultyMaxCap();

    /** Proximity sub-grid size (chunks per side) for the region-power bucket. GLOBAL (grid consistency). */
    int getRegionSizeChunks();

    /** Max absolute difficulty swing the region-power group delta may add over the floor. */
    double getGroupDeltaBandWidth();

    /** When true, the group delta may only RAISE difficulty over the floor, never soften it. */
    boolean isOnlyRaiseDifficulty();

    /**
     * Whether player/group-based scaling (the region-power group delta) applies in this world (1.0.1).
     * {@code false} pins difficulty to the escalated floor regardless of nearby player power - the
     * per-world toggle authored instances (e.g. a fixed-difficulty dungeon) use.
     */
    boolean isPlayerScalingEnabled();

    /** The open-world group-power aggregation mode name (folded through {@code AggregationMode.fromName}). */
    @Nonnull
    String getOpenWorldAggregationMode();

    /**
     * The difficulty -> stat curve for this world, with the effective {@code Intensity} multiplier
     * ALREADY applied to the three slopes (1.0.1). The single production {@code MobScaleFold.fold} +
     * the inspect preview read this, so intensity + per-world stat-curve overrides flow through here.
     */
    @Nonnull
    MobScaleFold.DifficultyStatCurve statCurveModel();
}
