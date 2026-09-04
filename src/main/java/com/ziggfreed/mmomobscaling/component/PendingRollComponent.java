package com.ziggfreed.mmomobscaling.component;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;

/**
 * The ONE-TICK marker between a mob passing the spawn hook's gates and its rarity roll. The spawn
 * hook runs before the entity has a {@code Ref}, and two reads the roll depends on are only there
 * once it does: the engine attaches a spawned NPC's {@code SpawnMarkerReference} in a post-spawn
 * step that runs AFTER the store's add, and the encounter framework indexes a bound boss on its own
 * tick. So the hook stamps this on the holder and {@code MobScalingRollSystem} finds it on the first
 * tick, with a valid {@code Ref} in hand, rolls (or skips) and removes it. A TRANSIENT component
 * (registered with a {@code Supplier}, no codec): it never lives longer than one tick and never
 * reaches a chunk save.
 *
 * <p>Carries the classification the hook already decided, so the tick does not repeat it.
 */
public final class PendingRollComponent implements Component<EntityStore> {

    private final byte scope;

    /** Engine default-supplier constructor: a hostile scope (the hook always stamps a populated one). */
    public PendingRollComponent() {
        this(MobScaleResult.SCOPE_HOSTILE);
    }

    public PendingRollComponent(byte scope) {
        this.scope = scope;
    }

    /** The scope byte the spawn hook classified this mob with ({@code MobScaleResult.SCOPE_*}). */
    public byte scope() {
        return scope;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        return new PendingRollComponent(scope);
    }

    /** The registered component type (resolved via the plugin singleton; registered in {@code setup()}). */
    @Nonnull
    public static ComponentType<EntityStore, PendingRollComponent> getComponentType() {
        return MobScalingPlugin.getInstance().getPendingRollComponentType();
    }
}
