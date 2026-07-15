package com.ziggfreed.mmomobscaling.component;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.caster.CasterEntry;

/**
 * The TRANSIENT per-mob armed-caster state, stamped once at spawn by {@code MobScalingCasterArmSystem}
 * (mirrors {@code ScaledMobComponent}: a {@code Supplier}-registered component, no codec, re-derived
 * identically on every {@code onEntityAdd} from the same {@code CasterRosterMatcher} resolve - a chunk
 * reload re-arms the same entries without persistence). Holds ONLY the {@link CasterEntry}s that
 * cleared {@link CasterEntry#isEligible} for this mob's roll, each paired with its OWN mutable
 * {@link Armed#nextCastAtMs()} due-timer ticked by {@code MobScalingCasterTickSystem}.
 *
 * <p>A mob with no matching roster (or a roster with zero eligible entries) never gets this component
 * at all - the cadence ticking system's {@code Archetype} query excludes it entirely, so the common
 * "not a caster" case costs nothing per tick (not even a boolean read).
 */
public final class CasterKitComponent implements Component<EntityStore> {

    @Nonnull
    private final List<Armed> armed;

    /** Engine default-supplier constructor: no armed entries (overwritten by the populated add). */
    public CasterKitComponent() {
        this.armed = List.of();
    }

    public CasterKitComponent(@Nonnull List<Armed> armed) {
        this.armed = List.copyOf(armed);
    }

    @Nonnull
    public List<Armed> armed() {
        return armed;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        List<Armed> copy = new ArrayList<>(armed.size());
        for (Armed a : armed) {
            copy.add(a.copy());
        }
        return new CasterKitComponent(copy);
    }

    /** The registered component type (resolved via the plugin singleton; registered in {@code setup()}). */
    @Nonnull
    public static ComponentType<EntityStore, CasterKitComponent> getComponentType() {
        return MobScalingPlugin.getInstance().getCasterKitComponentType();
    }

    /**
     * One armed roster entry + its own next-due timer (epoch millis). Mutated in place by the ticking
     * system on the entity's own world thread (the same convention {@code ScaledMobComponent.bonusLootDropped}
     * uses); {@link #copy()} snapshots the current due time so an archetype move mid-cadence keeps ticking
     * from where it left off rather than resetting.
     */
    public static final class Armed {

        @Nonnull
        private final CasterEntry entry;
        private volatile long nextCastAtMs;

        public Armed(@Nonnull CasterEntry entry, long nextCastAtMs) {
            this.entry = entry;
            this.nextCastAtMs = nextCastAtMs;
        }

        @Nonnull
        public CasterEntry entry() {
            return entry;
        }

        public long nextCastAtMs() {
            return nextCastAtMs;
        }

        public void setNextCastAtMs(long v) {
            nextCastAtMs = v;
        }

        @Nonnull
        Armed copy() {
            return new Armed(entry, nextCastAtMs);
        }
    }
}
