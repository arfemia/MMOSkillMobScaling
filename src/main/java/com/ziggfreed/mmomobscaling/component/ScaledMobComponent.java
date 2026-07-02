package com.ziggfreed.mmomobscaling.component;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.scaling.MobScaleFold;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;

/**
 * The FROZEN per-mob scaling data, stamped once at spawn by {@code MobScalingSpawnHook} and read (never
 * recomputed) by the damage filter + effect-apply + death path. A TRANSIENT component (registered with a
 * {@code Supplier}, no codec): it is re-derived identically on every {@code onEntityAdd} from the stable
 * spawn seed, so a chunk reload reproduces the same rarity/affixes without persistence (the gap-note
 * lifecycle decision).
 *
 * <p>Holds an immutable {@link MobScaleResult}; {@link #clone()} shares that reference (safe - it is
 * immutable). The no-arg constructor (used by the engine's default {@code Supplier}) yields a benign plain
 * result so an unpopulated instance is never harmful; the spawn hook adds a populated instance via
 * {@code Holder.addComponent}.
 *
 * <p>The ONE mutable field is {@link #bonusLootDropped()}, a death-path latch so the bonus-loot ticking
 * system pays a corpse exactly once (mirrors the vanilla {@code Role.hasDroppedDeathItems} latch);
 * {@link #clone()} copies it so an archetype move mid-death cannot re-arm the drop.
 */
public final class ScaledMobComponent implements Component<EntityStore> {

    @Nonnull
    private final MobScaleResult result;

    /** Death-path latch: set once by {@code MobScalingLootDropSystem} when the bonus loot has dropped. */
    private volatile boolean bonusLootDropped;

    /** Engine default-supplier constructor: a benign plain result (overwritten by the populated add). */
    public ScaledMobComponent() {
        this.result = MobScaleFold.plain(0.0, MobScaleResult.SCOPE_HOSTILE);
    }

    public ScaledMobComponent(@Nonnull MobScaleResult result) {
        this.result = result;
    }

    @Nonnull
    public MobScaleResult result() {
        return result;
    }

    /** True once the death bonus loot has been dropped for this mob (one-shot latch). */
    public boolean bonusLootDropped() {
        return bonusLootDropped;
    }

    /** Latch the death bonus loot as dropped (never unlatched; the corpse is removed shortly after). */
    public void markBonusLootDropped() {
        bonusLootDropped = true;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        ScaledMobComponent copy = new ScaledMobComponent(result); // result is immutable - safe to share
        copy.bonusLootDropped = bonusLootDropped;
        return copy;
    }

    /** The registered component type (resolved via the plugin singleton; registered in {@code setup()}). */
    @Nonnull
    public static ComponentType<EntityStore, ScaledMobComponent> getComponentType() {
        return MobScalingPlugin.getInstance().getScaledMobComponentType();
    }
}
