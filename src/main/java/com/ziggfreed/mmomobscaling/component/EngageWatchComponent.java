package com.ziggfreed.mmomobscaling.component;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.affix.AffixCry;

/**
 * The TRANSIENT watch on a scaled mob for what it does once it turns on a target, stamped at the roll and ticked by
 * {@code event/MobScalingEngageSystem}: whether it still has to count the party in front of it (the first time it
 * turns on a player), and the war cries its affixes give it, each with its own timers. A mob with neither never
 * carries one, so the system's query costs nothing for it, and the watch comes off once nothing is left to do (the
 * party counted and no cry). Like {@link CasterKitComponent}: a {@code Supplier}-registered component, no codec,
 * re-stamped identically by the roll after a chunk reload.
 *
 * <p>Mutated in place on the entity's own world thread; {@link #clone()} copies every timer, so an archetype move
 * mid-cry keeps its place.
 */
public final class EngageWatchComponent implements Component<EntityStore> {

    private volatile boolean partyPending;
    @Nonnull private final List<Cry> cries;
    /** The next time (epoch millis) the system looks at this mob's target again. */
    private volatile long nextLookAtMs;

    /** Engine default-supplier constructor: nothing to watch (overwritten by the populated stamp). */
    public EngageWatchComponent() {
        this(false, List.of());
    }

    public EngageWatchComponent(boolean partyPending, @Nonnull List<AffixCry> cries) {
        this.partyPending = partyPending;
        List<Cry> armed = new ArrayList<>(cries.size());
        for (AffixCry cry : cries) {
            armed.add(new Cry(cry));
        }
        this.cries = List.copyOf(armed);
    }

    private EngageWatchComponent(boolean partyPending, @Nonnull List<Cry> cries, long nextLookAtMs) {
        this.partyPending = partyPending;
        this.cries = cries;
        this.nextLookAtMs = nextLookAtMs;
    }

    /** Is there anything to watch: a party to count or a cry to give? */
    public static boolean worthWatching(boolean partyPending, @Nonnull List<AffixCry> cries) {
        return partyPending || !cries.isEmpty();
    }

    /** Has the mob still to count the party in front of it? */
    public boolean partyPending() {
        return partyPending;
    }

    /** The party is counted (or will never be): the watch stops asking. */
    public void partyCounted() {
        partyPending = false;
    }

    /** The mob's war cries, each with its own timers. */
    @Nonnull
    public List<Cry> cries() {
        return cries;
    }

    /** Nothing left to do: the party counted and no cry to give. */
    public boolean done() {
        return !partyPending && cries.isEmpty();
    }

    public long nextLookAtMs() {
        return nextLookAtMs;
    }

    public void setNextLookAtMs(long v) {
        nextLookAtMs = v;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        List<Cry> copy = new ArrayList<>(cries.size());
        for (Cry cry : cries) {
            copy.add(cry.copy());
        }
        return new EngageWatchComponent(partyPending, List.copyOf(copy), nextLookAtMs);
    }

    /** The registered component type (resolved via the plugin singleton; registered in {@code setup()}). */
    @Nonnull
    public static ComponentType<EntityStore, EngageWatchComponent> getComponentType() {
        return MobScalingPlugin.getInstance().getEngageWatchComponentType();
    }

    /**
     * One war cry and its timers: when the mob may cry next (0, the first cry, comes as soon as it turns on a
     * target), when the cry under way lands (0 when none is), and the target it is crying against.
     */
    public static final class Cry {

        @Nonnull private final AffixCry cry;
        private volatile long nextCryAtMs;
        private volatile long landsAtMs;
        @Nullable private volatile Ref<EntityStore> target;

        Cry(@Nonnull AffixCry cry) {
            this.cry = cry;
        }

        @Nonnull
        public AffixCry cry() {
            return cry;
        }

        public long nextCryAtMs() {
            return nextCryAtMs;
        }

        public long landsAtMs() {
            return landsAtMs;
        }

        @Nullable
        public Ref<EntityStore> target() {
            return target;
        }

        /** A cry begins against {@code against}, landing at {@code landsAt}. */
        public void begin(@Nonnull Ref<EntityStore> against, long landsAt) {
            this.target = against;
            this.landsAtMs = landsAt;
        }

        /** The cry landed (or was dropped): the next comes at {@code nextAt}. */
        public void landed(long nextAt) {
            this.target = null;
            this.landsAtMs = 0L;
            this.nextCryAtMs = nextAt;
        }

        /** Hold the next cry until {@code nextAt} without one under way (the mob already wears a rally). */
        public void holdUntil(long nextAt) {
            this.nextCryAtMs = nextAt;
        }

        @Nonnull
        Cry copy() {
            Cry copy = new Cry(cry);
            copy.nextCryAtMs = nextCryAtMs;
            copy.landsAtMs = landsAtMs;
            copy.target = target;
            return copy;
        }
    }
}
