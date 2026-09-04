package com.ziggfreed.mmomobscaling.world;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.mmomobscaling.scaling.RegionPowerTracker;

/**
 * The ONE way a position becomes a {@link RegionPowerTracker.RegionKey}: the native worldgen zone
 * the chunk sits in (through the memoized {@link ZoneDifficultyResolver}) plus the chunk sub-grid
 * cell within it. The presence tick that WRITES a player into a bucket, the factor that READS the
 * region a moment happened in, and the encounter power fill that reads the region a boss stands in
 * all compose the key here, so a bucket written from one place is always found from the others.
 */
public final class RegionKeys {

    private RegionKeys() {
    }

    /** The key of the region holding chunk {@code (chunkX, chunkZ)} in {@code world}. */
    @Nonnull
    public static RegionPowerTracker.RegionKey at(@Nonnull World world, int chunkX, int chunkZ, int regionSizeChunks) {
        return new RegionPowerTracker.RegionKey(ZoneDifficultyResolver.get().zoneKey(world, chunkX, chunkZ),
                RegionPowerTracker.gridKey(chunkX, chunkZ, regionSizeChunks));
    }

    /**
     * The key of the region the positioned entity {@code ref} stands in, or null when it has no
     * position to read (no transform on the entity).
     */
    @Nullable
    public static RegionPowerTracker.RegionKey of(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> ref, @Nonnull World world, int regionSizeChunks) {
        TransformComponent transform = accessor.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            return null;
        }
        return at(world, ChunkUtil.chunkCoordinate(transform.getPosition().x),
                ChunkUtil.chunkCoordinate(transform.getPosition().z), regionSizeChunks);
    }
}
