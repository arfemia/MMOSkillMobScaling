package com.ziggfreed.mmomobscaling.event;

import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.scaling.AggregationMode;
import com.ziggfreed.mmoskilltree.api.MMOSkillTreeAPI;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.config.MobScalingConfig;
import com.ziggfreed.mmomobscaling.scaling.RegionPowerTracker;

/**
 * The player-move bookkeeping behind {@link RegionPowerTracker}: ticks every player, computes their
 * region-grid cell from the chunk coords, and ONLY on a region/world cross (or first sight) reads
 * the player's power off the frozen {@code MMOSkillTreeAPI} and moves their tracked presence (which
 * re-folds the affected region buckets). The per-tick steady-state cost per player is one map read +
 * a key compare - the plan's "refreshed on player chunk-cross" cadence, never a per-spawn scan.
 *
 * <p>The nested {@link Removal} {@code RefSystem} drops a player's presence when their entity leaves
 * the store (disconnect / world switch-out / unload), so a bucket never holds a ghost. On a world
 * SWITCH the next presence tick in the new world re-registers them; power is also re-read on every
 * cross, so a leveling player's contribution tracks their growth region by region. Whole bodies
 * try-guarded.
 */
public final class MobScalingPresenceSystem extends EntityTickingSystem<EntityStore> {

    @Nonnull
    private final ComponentType<EntityStore, TransformComponent> transformType = TransformComponent.getComponentType();

    @Nonnull
    private final Query<EntityStore> query = Query.and(Player.getComponentType(), transformType);

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            UUIDComponent uuidComp = archetypeChunk.getComponent(index, UUIDComponent.getComponentType());
            UUID playerId = uuidComp != null ? uuidComp.getUuid() : null;
            if (playerId == null) {
                return;
            }
            World world = store.getExternalData().getWorld();
            if (world == null) {
                return;
            }
            TransformComponent transform = archetypeChunk.getComponent(index, transformType);
            if (transform == null) {
                return;
            }
            MobScalingConfig cfg = MobScalingConfig.getInstance();
            long regionKey = RegionPowerTracker.regionKey(
                    ChunkUtil.chunkCoordinate(transform.getPosition().x),
                    ChunkUtil.chunkCoordinate(transform.getPosition().z),
                    cfg.getRegionSizeChunks());
            String worldKey = world.getName();
            if (RegionPowerTracker.get().isCurrent(playerId, worldKey, regionKey)) {
                return; // steady state: no cross, nothing to do
            }
            double power = MMOSkillTreeAPI.getPowerLevel(store, archetypeChunk.getReferenceTo(index));
            RegionPowerTracker.get().updatePresence(playerId, worldKey, regionKey, power, mode(cfg));
        } catch (Throwable t) {
            safeWarn("presence tick failed: " + t);
        }
    }

    /** The configured open-world fold; AVERAGE when unset/unrecognised (the shipped default). */
    @Nonnull
    public static AggregationMode mode(@Nonnull MobScalingConfig cfg) {
        return AggregationMode.fromName(cfg.getOpenWorldAggregationMode(), AggregationMode.AVERAGE);
    }

    /** Drops a removed player entity's tracked presence (disconnect / world switch / unload). */
    public static final class Removal extends RefSystem<EntityStore> {

        @Nonnull
        private final Query<EntityStore> query = Player.getComponentType();

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return query;
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> cb) {
            // No-op: the presence ticking system registers the player on its first tick.
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> cb) {
            try {
                UUIDComponent uuidComp = store.getComponent(ref, UUIDComponent.getComponentType());
                UUID playerId = uuidComp != null ? uuidComp.getUuid() : null;
                if (playerId != null) {
                    RegionPowerTracker.get().removePresence(playerId, mode(MobScalingConfig.getInstance()));
                }
            } catch (Throwable t) {
                safeWarn("presence removal failed: " + t);
            }
        }
    }

    private static void safeWarn(@Nonnull String message) {
        try {
            MobScalingPlugin.LOGGER.atWarning().log(message);
        } catch (Throwable ignored) {
            // log-manager-less JVMs
        }
    }
}
