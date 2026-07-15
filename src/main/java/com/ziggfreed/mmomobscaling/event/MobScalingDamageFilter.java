package com.ziggfreed.mmomobscaling.event;

import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.mmoskilltree.event.CombatDamageEventSystem;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.component.ScaledMobComponent;

/**
 * The per-hit DAMAGE-MULTIPLY filter: a {@link DamageEventSystem} in {@code DamageModule.getFilterDamageGroup()}
 * (so it sees ALL damage), reading the FROZEN {@code ScaledMobComponent} mults - zero affix walk on the common
 * path. A scaled ATTACKER scales its OUTGOING damage by {@code outDmgMult}; a scaled VICTIM scales INCOMING
 * damage by {@code inDmgMult}. Both directions are handled by checking attacker + victim independently.
 *
 * <p><b>Ordering ({@link #getDependencies}):</b> pinned {@code BEFORE} the MMO's
 * {@code CombatDamageEventSystem} (crit multiplier + defense reduction - the other FILTER-GROUP damage
 * MODIFIER) so our scaling multiply lands before that math, and {@code BEFORE} the vanilla
 * {@code ArmorDamageReduction} so the multiply lands before armor's flat subtraction (multiplying
 * post-armor would make flat armor disproportionately strong against scaled mobs).
 *
 * <p><b>1.1.0 retarget (was {@code CombatXpEventSystem}):</b> the MMO moved {@code CombatXpEventSystem}
 * out of the Filter group into the INSPECT group (a passive XP-read, not a damage modifier - it now
 * reads {@code damage.getAmount()} post-{@code ApplyDamage}, the same FINAL value
 * {@link MobScalingOnHitSystem} reads). {@code SystemDependency} resolves purely by CLASS across the
 * whole store's dependency graph (group-agnostic - see {@code SystemDependency.resolveGraphEdge} in the
 * shared source), so the OLD dependency kept validating and resolving correctly even after that move
 * (Filter always structurally precedes Inspect, so XP crediting still observed the scaled amount) - it
 * was not broken, just redundant and pointed at a system no longer in this phase. Retargeting to
 * {@code CombatDamageEventSystem} (confirmed still Filter-group) restores a same-phase ordering
 * guarantee against this filter's actual peer and drops the latent risk of {@code SystemDependency
 * .validate()} throwing {@code IllegalArgumentException} if a future MMO build removes/renames
 * {@code CombatXpEventSystem} outright rather than just moving its group.
 *
 * <p><b>Behavioral affixes are NOT here.</b> Lifesteal + the Freezing on-hit slow moved to
 * {@link MobScalingOnHitSystem} (the INSPECT group), where {@code damage.getAmount()} is the FINAL applied value
 * (post-armor, post-MMO-defense, post-rounding, dead-victim already cancelled) - so Vampiric heals off real
 * damage and Freezing never fires on a fully-blocked hit. This system does the scalar multiply only.
 * Whole body try-guarded; never initiates damage.
 */
public final class MobScalingDamageFilter extends DamageEventSystem {

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            // Filter-phase peer ordering: our scaling multiply lands before the MMO's own crit/defense math.
            new SystemDependency<>(Order.BEFORE, CombatDamageEventSystem.class),
            // Multiply BEFORE armor's flat subtraction (verifier: post-armor multiply over-weights flat armor).
            new SystemDependency<>(Order.BEFORE, DamageSystems.ArmorDamageReduction.class));

    @Nonnull
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Query.any();
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> cb, @Nonnull Damage damage) {
        try {
            if (damage.isCancelled()) {
                return;
            }
            float amount = damage.getAmount();
            if (amount <= 0f) {
                return;
            }

            Ref<EntityStore> victimRef = chunk.getReferenceTo(index);
            ScaledMobComponent victimComp = validComp(store, victimRef);
            Ref<EntityStore> attackerRef = attackerOf(damage);
            ScaledMobComponent attackerComp = validComp(store, attackerRef);

            // Self-hit (a scaled mob caught in its own AoE): treat as victim-only so it is not DOUBLE-scaled
            // (out * in on the same mob) and behavioral affixes never fire on self. Identity compare is correct
            // (Ref does not override equals; the chunk + engine source pass the canonical stored Ref instance).
            if (attackerRef == victimRef) {
                attackerComp = null;
            }

            if (victimComp == null && attackerComp == null) {
                return; // neither party is a scaled mob - untouched
            }

            float scaled = amount;
            if (attackerComp != null) {
                scaled *= attackerComp.result().outDmgMult(); // mob dealing damage
            }
            if (victimComp != null) {
                scaled *= victimComp.result().inDmgMult(); // mob taking damage (tankiness)
            }
            if (scaled != amount) {
                damage.setAmount(Math.max(0f, scaled));
            }
        } catch (Throwable t) {
            safeWarn("damage filter failed: " + t);
        }
    }

    @Nullable
    private static ScaledMobComponent validComp(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) {
            return null;
        }
        return store.getComponent(ref, ScaledMobComponent.getComponentType());
    }

    @Nullable
    private static Ref<EntityStore> attackerOf(@Nonnull Damage damage) {
        return damage.getSource() instanceof Damage.EntitySource es ? es.getRef() : null;
    }

    private static void safeWarn(@Nonnull String message) {
        try {
            MobScalingPlugin.LOGGER.atWarning().log(message);
        } catch (Throwable ignored) {
            // log-manager-less JVMs
        }
    }
}
