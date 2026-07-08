package com.ziggfreed.mmomobscaling.config;

import javax.annotation.Nonnull;

import com.ziggfreed.mmomobscaling.scaling.MobScaleFold;

/**
 * The spawn-time settings surface a mob-scaling spawn resolves against - the exact getters
 * {@code ZoneDifficultyResolver.resolve} + {@code MobScalingSpawnHook.resolveSpawnScaling} +
 * {@code MobScalingPresenceSystem.mode} (+ the HUD tick) read per world. {@link MobScalingConfig}
 * implements it (the GLOBAL folded values); {@code MobScalingConfig.spawnSettingsFor(worldName)}
 * returns a per-world OVERLAY view ({@link ResolvedWorldSettings}) for a world matched by a
 * {@code Worlds/*.json} rule (1.0.2), or the config itself when nothing matches (zero-alloc
 * common case).
 *
 * <p>This is the ONLY seam the per-world overlay flows through: the resolver + hook take a
 * {@code SpawnScalingSettings} rather than the {@code MobScalingConfig} singleton, so an authored
 * world file tunes a dungeon's kill-switch / baseline floor / rarity chance / difficulty caps /
 * stat curve / intensity / open-world behavior / HUD visibility / rarity-variant-affix pool
 * without touching the global config. The ONE field that stays global by design is
 * {@code RegionSizeChunks} (region-grid consistency) - see the router.
 */
public interface SpawnScalingSettings {

    /**
     * The per-world kill-switch (1.0.2; absorbs the removed hyMMO {@code WorldRules.MobScaling.Enabled}):
     * {@code false} = no mob scaling in this world (the spawn hook strips residue instead of scaling).
     * The GLOBAL view returns the folded {@code Enabled}.
     */
    boolean isWorldScalingEnabled();

    /**
     * The WORLD-BASELINE difficulty floor (1.0.2; absorbs the removed hyMMO
     * {@code WorldRules.MobScaling.DifficultyFloor}): the LOWEST-precedence floor under the authored
     * zone/biome {@code Difficulty/*.json} mappings - used only when no mapping matches. {@code 0.0} = none.
     */
    double getDifficultyFloor();

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

    /** One-shot additive difficulty bump allowed when a stronger player/party arrives (per-world, 1.0.2). */
    boolean isAllowDifficultyIncreaseOnPartyJoin();

    /** Size (flat additive difficulty) of the late-arrival bump (per-world, 1.0.2). */
    double getLateArrivalBumpFactor();

    /** Open-world density/composition scaling toggle (per-world, 1.0.2). */
    boolean isCompositionEnabled();

    /**
     * Whether the zone-difficulty HUD shows in this world (1.0.2). A per-world {@code false} HIDES the
     * HUD where the global is on; a per-world {@code true} cannot re-enable a globally-off HUD (the
     * global early-out is kept as the cheap fast path - documented limitation).
     */
    boolean isZoneHudEnabled();

    /** Whether the mob-inspector HUD shows in this world (1.0.2; same hide-only semantics as the zone HUD). */
    boolean isInspectorHudEnabled();

    /** Whether a rarity tier may roll in this world ({@code Pool.Rarities} allow/deny; deny wins). */
    boolean isRarityAllowed(@Nonnull String rarityId);

    /** Whether a variant overlay may roll in this world ({@code Pool.Variants} allow/deny; deny wins). */
    boolean isVariantAllowed(@Nonnull String variantId);

    /** Whether an affix may roll in this world ({@code Pool.Affixes} allow/deny; deny wins). */
    boolean isAffixAllowed(@Nonnull String affixId);

    /** Per-world multiplier on every eligible variant's absolute roll chance ({@code >= 0}; 1.0 neutral). */
    double getVariantChanceMultiplier();

    /** Extra per-world affix slots stacked on the rarity/variant slots ({@code >= 0}). */
    int getExtraAffixSlots();

    /**
     * The difficulty -> stat curve for this world, with the effective {@code Intensity} multiplier
     * ALREADY applied to the three slopes (1.0.1). The single production {@code MobScaleFold.fold} +
     * the inspect preview read this, so intensity + per-world stat-curve overrides flow through here.
     */
    @Nonnull
    MobScaleFold.DifficultyStatCurve statCurveModel();
}
