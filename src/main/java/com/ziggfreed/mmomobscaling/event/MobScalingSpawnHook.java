package com.ziggfreed.mmomobscaling.event;

import java.util.Set;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsSystems;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.systems.RoleBuilderSystem;
import com.ziggfreed.common.health.HealthUtil;
import com.ziggfreed.common.scaling.ScalingContext;
import com.ziggfreed.common.scaling.ScalingEngine;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.component.PendingRollComponent;
import com.ziggfreed.mmomobscaling.component.ScaledMobComponent;
import com.ziggfreed.mmomobscaling.config.MobScalingConfig;
import com.ziggfreed.mmomobscaling.config.SpawnScalingSettings;
import com.ziggfreed.mmomobscaling.scaling.MobScaleFold;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;
import com.ziggfreed.mmomobscaling.scaling.RegionPowerTracker;
import com.ziggfreed.mmomobscaling.world.ZoneDifficultyResolver;

/**
 * The spawn-lock: a {@link HolderSystem} over the structural {@code Archetype.of(NPCEntity, EntityStatMap)}
 * query (copied from {@code BalancingInitialisationSystem}), ordered {@code AFTER RoleBuilderSystem +
 * EntityStatsSystems.Setup} so the role + stat map are built. It runs BEFORE the entity has a
 * {@code Ref}, so it decides only what a pre-add holder can answer: the mod and per-world kill
 * switches, the classification (excluded / hostile / ambient boss), and the residue cleanup for a
 * mob that must NOT be scaled on this load. A scalable mob gets a {@link PendingRollComponent}
 * stamped on the holder and nothing else; {@link MobScalingRollSystem} rolls it on the first tick,
 * with a valid ref, where the two skips a scripted spawn and an encounter-bound boss need are
 * readable (the engine attaches a mob's {@code SpawnMarkerReference} in a post-spawn step that runs
 * AFTER the store's add, and the encounter framework indexes a bound subject on its own tick).
 *
 * <p><b>Rolled at most once per entity per life.</b> An in-place role change is a remove and a
 * re-add: the engine's {@code RoleChangeSystem} hands the holder back with its components and adds
 * it again through every registered {@code HolderSystem} with a fresh {@code Ref}, at every phase
 * swap of a multi-phase boss. A holder that already carries a {@link ScaledMobComponent} was decided
 * in an earlier life of this same holder and is left as it is, so a boss is never re-rolled or
 * re-scaled mid-fight. (A chunk reload is a different case: the component is transient, so a reloaded
 * mob carries none and is rolled again from its stable seed to the identical result, which is what
 * reconciles a retune onto a saved mob.) The residue cleanup runs BEFORE that guard, so a mod- or
 * world-disabled mob and a newly excluded one are still stripped on every load.
 *
 * <p><b>Difficulty scope:</b> {@code effDifficulty} = the LAYERED floor (native zone &gt; biome &gt;
 * the per-world/global {@code Difficulty.Floor} baseline, via {@link ZoneDifficultyResolver}) plus the
 * distance-from-spawn escalation, plus the band-clamped open-world GROUP DELTA off the cached
 * per-(zone, sub-grid) player-power scalar ({@link RegionPowerTracker} + ziggfreed-common's
 * {@code ScalingEngine}; see {@link #resolveSpawnScaling}), resolved by the roll system on its tick.
 * The whole body is one defensive try/catch so a throw never breaks chunk loading.
 */
public final class MobScalingSpawnHook extends HolderSystem<EntityStore> {

    /** Mod-prefixed HP-modifier key (the {@code reconcileMaxHealth} idempotency handle; the purge command strips it too). */
    public static final String HP_KEY = "mmoscaling_hp";

    @Nonnull private final ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
    @Nonnull private final ComponentType<EntityStore, EntityStatMap> statType = EntityStatMap.getComponentType();

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, RoleBuilderSystem.class),
            new SystemDependency<>(Order.AFTER, EntityStatsSystems.Setup.class));

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(npcType, statType);

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void onEntityAdd(@Nonnull Holder<EntityStore> holder, @Nonnull AddReason reason,
            @Nonnull Store<EntityStore> store) {
        // Captured before the body so the catch-all below can name WHICH mob failed (a bare stack-class
        // warn is unactionable in a bug report).
        String failRole = "?";
        try {
            MobScalingConfig cfg = MobScalingConfig.getInstance();
            if (!cfg.isEnabled()) {
                cleanupResidue(holder); // runtime soft-disable: strip any stale scaling off saved mobs
                return;
            }
            World world = store.getExternalData().getWorld();
            if (world == null) {
                return;
            }
            // ONE per-world settings resolve (cached view; the GLOBAL config when no Worlds/*.json rule
            // matches): the kill-switch, the floor, the caps, the pool gates - everything reads off it.
            SpawnScalingSettings spawn = cfg.spawnSettingsFor(world);
            if (!spawn.isWorldScalingEnabled()) {
                cleanupResidue(holder); // per-world kill-switch flipped off: strip stale scaling
                return;
            }

            NPCEntity npc = holder.getComponent(npcType);
            if (npc == null) {
                return; // guaranteed by the structural query, but guard anyway
            }
            String roleName = npc.getRoleName();
            if (roleName != null) {
                failRole = roleName;
            }
            Byte scope = MobClassifier.classify(npc, holder);
            if (scope == null) {
                cleanupResidue(holder); // now EXCLUDED (e.g. a role added to the exclude set): strip stale scaling
                return;
            }
            if (holder.getComponent(ScaledMobComponent.getComponentType()) != null) {
                return; // decided in an earlier life of this holder (an in-place role change re-adds it): rolled once
            }
            // The roll itself waits for the first tick, where a valid Ref makes the two skips readable.
            holder.putComponent(PendingRollComponent.getComponentType(), new PendingRollComponent(scope));
        } catch (Throwable t) {
            safeWarn("spawn scale failed for role " + failRole + ": " + t);
        }
    }

    @Override
    public void onEntityRemoved(@Nonnull Holder<EntityStore> holder, @Nonnull RemoveReason reason,
            @Nonnull Store<EntityStore> store) {
        // No-op: the transient component + native effect clear on death own teardown; nothing to release.
    }

    /**
     * Strip stale native scaling off a mob that should NOT be scaled on this load (mod runtime-disabled, world
     * kill-switch off, or newly excluded). Removes the {@code mmoscaling_hp} MAX modifier; if there WAS residue
     * (the mob was scaled before), REPLACES any existing {@link ScaledMobComponent} with a PLAIN one (an
     * idempotent {@code putComponent}, so a re-added holder that still carries the component never throws) so
     * the effect {@code RefSystem} fires and sweeps the stale {@code Mmoscaling_*} auras the same add cycle.
     * Cheap no-op for a mob that was never scaled (the modifier is absent, so nothing is stamped and no
     * per-entity cost is added).
     */
    private static void cleanupResidue(@Nonnull Holder<EntityStore> holder) {
        boolean hadResidue = HealthUtil.reconcileMaxHealth(holder, 1.0, HP_KEY);
        if (hadResidue) {
            holder.putComponent(ScaledMobComponent.getComponentType(),
                    new ScaledMobComponent(MobScaleFold.plain(0.0, MobScaleResult.SCOPE_HOSTILE,
                            MobScaleFold.DifficultyStatCurve.NONE)));
        }
    }

    /**
     * One fully-resolved spawn-scaling read: the effective difficulty (floor + escalation + group
     * delta), the escalation-boosted rarity spawn chance, and the diagnostic breakdown
     * ({@code /mobscaling inspect} prints every field so owners can see exactly which layer produced
     * a number).
     *
     * <p>{@code insideStartRing} + {@code playerScalingApplied} are the WHY of the group delta: a zero
     * delta is otherwise indistinguishable between "no players tracked", "player scaling switched off in
     * this world", and "inside the protected ring near world spawn".
     */
    public record SpawnScaling(double difficulty, double raritySpawnChance, @Nonnull String zoneName,
            double baseFloor, double escalationBonus, double effectiveFloor, double regionPower,
            @Nonnull String biomeName, boolean insideStartRing, boolean playerScalingApplied,
            double distanceFromSpawn) {
    }

    /**
     * The chunk-coordinate form of the spawn-scaling resolve, shared by the roll system, the HUD and the
     * {@code /mobscaling inspect} diagnostic so all three report EXACTLY what a spawn at that spot
     * resolves. Pipeline: {@link ZoneDifficultyResolver} produces the layered floor (native zone &gt;
     * biome &gt; world baseline) plus the distance escalation (which also boosts the rarity chance);
     * then the cached per-(zone, sub-grid) player-power scalar ({@link RegionPowerTracker}, O(1),
     * maintained on player region-cross, NEVER a per-spawn scan) rides on top through
     * ziggfreed-common's {@code ScalingEngine} under the configured aggregation mode + band/caps. A
     * cold region (no players tracked, scalar {@code <= 0}) is a ZERO delta: the escalated floor
     * stands. Power scaling is also fully OFF inside the PROTECTED RING near world spawn
     * ({@code floor.insideStartRing()}, sized by its own {@code OpenWorld.PlayerScalingStartRingBlocks}
     * knob and OFF by default at radius 0 - never the distance-escalation start radius), and when
     * {@code OnlyRaiseDifficulty} is set the group delta only ever RAISES difficulty above the floor,
     * never below it. This is what makes {@code MinDifficulty} above the floor a live lever (a strong
     * group pushes {@code effDifficulty} past the floor, so Legendary / Freezing bands become reachable).
     */
    public static SpawnScaling resolveSpawnScaling(@Nonnull World world,
            int chunkX, int chunkZ, @Nonnull SpawnScalingSettings settings) {
        ZoneDifficultyResolver.ResolvedFloor floor =
                ZoneDifficultyResolver.get().resolve(world, chunkX, chunkZ, settings);
        RegionPowerTracker.RegionKey regionKey = new RegionPowerTracker.RegionKey(floor.zoneName(),
                RegionPowerTracker.gridKey(chunkX, chunkZ, settings.getRegionSizeChunks()));
        double regionPower = RegionPowerTracker.get().scalarFor(world.getName(), regionKey);
        // LOCATION drives difficulty (the escalated floor). Player/group power only ever RAISES it above
        // that floor (never lowers it, when OnlyRaiseDifficulty is set), and is fully OFF inside the
        // PROTECTED RING near world spawn (OpenWorld.PlayerScalingStartRingBlocks, 0 = no ring), so an
        // owner who wants a safe newcomer home area opts into one. A world with PlayerScalingEnabled=false
        // (e.g. a fixed-difficulty dungeon) skips the group delta entirely and stays at the escalated floor.
        double difficulty = floor.effectiveFloor();
        boolean playerScalingApplied =
                settings.isPlayerScalingEnabled() && regionPower > 0.0 && !floor.insideStartRing();
        if (playerScalingApplied) {
            double scaled = ScalingEngine.resolve(
                    ScalingContext.openWorld(floor.effectiveFloor(), regionPower, MobScalingPresenceSystem.mode(settings)),
                    settings.getGroupDeltaBandWidth(), settings.getDifficultyMinCap(), settings.getDifficultyMaxCap());
            difficulty = settings.isOnlyRaiseDifficulty() ? Math.max(scaled, floor.effectiveFloor()) : scaled;
        }
        return new SpawnScaling(difficulty, floor.raritySpawnChance(), floor.zoneName(),
                floor.baseFloor(), floor.escalationBonus(), floor.effectiveFloor(), regionPower,
                floor.biomeName(), floor.insideStartRing(), playerScalingApplied, floor.distanceFromSpawn());
    }

    private static void safeWarn(@Nonnull String message) {
        try {
            MobScalingPlugin.LOGGER.atWarning().log(message);
        } catch (Throwable ignored) {
            // log-manager-less JVMs
        }
    }
}
