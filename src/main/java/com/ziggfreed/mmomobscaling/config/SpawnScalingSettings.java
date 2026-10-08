package com.ziggfreed.mmomobscaling.config;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
 * stat curve and clamps / open-world behavior / HUD placement and visibility /
 * rarity-variant-affix pool without touching the global config.
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

    /**
     * The authored block X of the point distance escalation is measured from
     * ({@code Difficulty.DistanceEscalation.Origin.X}), or {@code null} when unset at every layer: the
     * resolver then reads the world's own spawn point on that axis. Per axis, so one axis may be pinned
     * while the other follows the spawn point.
     */
    @Nullable
    Double getEscalationOriginX();

    /** The authored block Z of the escalation origin, or {@code null} for the world's spawn point (see {@link #getEscalationOriginX()}). */
    @Nullable
    Double getEscalationOriginZ();

    /** Escalation-free radius around the escalation origin (blocks, XZ Euclidean). */
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

    /**
     * Proximity sub-grid size (chunks per side) for the region-power bucket. A region bucket is keyed
     * by world, so the size only has to agree within one world; {@code RegionPowerTracker} purges a
     * world's tracked presence when its size changes, since every key composed under the old size
     * is stale.
     */
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

    /**
     * Protected radius around the world spawn (blocks, XZ Euclidean) inside which the player/group power
     * delta does NOT apply - so a newcomer's home area is never inflated by a passing strong group.
     * {@code 0} = no protected ring (player/group scaling applies everywhere). Fully INDEPENDENT of
     * {@link #getEscalationStartDistanceBlocks()}, which only gates the additive distance bonus.
     */
    double getPlayerScalingStartRingBlocks();

    /** The open-world group-power aggregation mode name (folded through {@code AggregationMode.fromName}). */
    @Nonnull
    String getOpenWorldAggregationMode();

    /**
     * Whether the zone-difficulty HUD shows in this world (1.0.2). A per-world {@code false} HIDES the
     * HUD where the global is on; a per-world {@code true} cannot re-enable a globally-off HUD (the
     * global early-out is kept as the cheap fast path - documented limitation).
     */
    boolean isZoneHudEnabled();

    /** Whether the mob-inspector HUD shows in this world (1.0.2; same hide-only semantics as the zone HUD). */
    boolean isInspectorHudEnabled();

    /**
     * The zone-difficulty HUD's corner preset name in this world ({@code HudPosition.parse} vocabulary;
     * a blank or unknown name falls back to the HUD's own default corner).
     */
    @Nonnull
    String getZoneHudPosition();

    /** Pixel offset of the zone-difficulty HUD from its anchored horizontal edge in this world. */
    int getZoneHudOffsetX();

    /** Pixel offset of the zone-difficulty HUD from its anchored vertical edge in this world. */
    int getZoneHudOffsetY();

    /** Whether the zone-difficulty HUD names the current zone (and biome, when keyed) in this world. */
    boolean isZoneShowLocationName();

    /** Lang-key prefix the zone name is looked up under in this world (blank = prettify the raw id). */
    @Nonnull
    String getZoneNameKeyPrefix();

    /** Lang-key prefix the biome name is looked up under in this world (blank = no biome line). */
    @Nonnull
    String getBiomeNameKeyPrefix();

    /** The mob-inspector HUD's corner preset name in this world (same vocabulary and fallback as the zone card). */
    @Nonnull
    String getInspectorHudPosition();

    /** Pixel offset of the mob-inspector HUD from its anchored horizontal edge in this world. */
    int getInspectorHudOffsetX();

    /** Pixel offset of the mob-inspector HUD from its anchored vertical edge in this world. */
    int getInspectorHudOffsetY();

    /** Crosshair-target search radius in blocks for the inspector raycast in this world (kept to a sane band). */
    double getInspectorRangeBlocks();

    /** Whether the inspector card shows the target's generated portrait in this world. */
    boolean isInspectorPortraitEnabled();

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
     * The difficulty -> stat curve for this world ({@code Difficulty.StatCurve}, every leaf per world).
     * The single production {@code MobScaleFold.fold} + the inspect preview read this, so a per-world
     * stat-curve override flows through here.
     */
    @Nonnull
    MobScaleFold.DifficultyStatCurve statCurveModel();

    /**
     * The safety rails for this world ({@code Difficulty.Clamps}, every leaf per world): the per-axis
     * floors and ceilings {@code MobScaleFold.fold} applies after the affix deltas.
     */
    @Nonnull
    MobScaleFold.Clamps clampsModel();
}
