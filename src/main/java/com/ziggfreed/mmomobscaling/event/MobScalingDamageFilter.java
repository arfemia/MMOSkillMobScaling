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
import com.ziggfreed.common.util.EntityIdentifierUtil;
import com.ziggfreed.mmoskilltree.event.CombatDamageEventSystem;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.component.ScaledMobComponent;
import com.ziggfreed.mmomobscaling.pages.RoleBaseHitResolver;

/**
 * The per-hit DAMAGE-MULTIPLY filter: a {@link DamageEventSystem} in {@code DamageModule.getFilterDamageGroup()}
 * (so it sees ALL damage), reading the FROZEN {@code ScaledMobComponent} mults - zero affix walk on the common
 * path. A scaled ATTACKER scales its OUTGOING damage by {@code outDmgMult}; a scaled VICTIM scales INCOMING
 * damage by {@code inDmgMult}. Both directions are handled by checking attacker + victim independently.
 *
 * <p><b>Ordering ({@link #getDependencies}):</b> pinned {@code BEFORE} the MMO's
 * {@code CombatDamageEventSystem} (crit multiplier + defense reduction - the other FILTER-GROUP damage
 * MODIFIER) so our scaling multiply lands before that math, and {@code BEFORE} the vanilla
 * {@code ArmorDamageReduction} so the scalar applies to the hit as the attacker rolled it rather than to
 * an already-mitigated number.
 *
 * <p><b>The {@code DamageSystems.ArmorDamageReduction} class literal is a DECISION, not an oversight
 * (maintainer ruling, 2026-09-28: it stays).</b> The engine marks that class deprecated, and its shared-source
 * javadoc says exactly "deprecated: Move to modifiers" and names no replacement. What this filter holds is a
 * class literal used only as an ordering target inside a {@code SystemDependency}: no method of the class is
 * ever called from here, so the family-wide ban on calling a deprecated engine API is not what this is. There
 * is no non-deprecated handle on the armor step to point at instead: in the installed 0.6.8 jar the armor math
 * still lives in {@code ArmorDamageReduction.handle}, {@code DamageModule.setup} still registers that system,
 * and {@code DamageModule} exposes only its three group getters. The engine's {@code DependencyGraph} admits an
 * edge into a system only by naming its class, its group or its type, and a group or type edge would land on
 * all seven of this filter's group peers rather than on the armor step alone, which is a different (and wrong)
 * ordering. So the class literal stays, with no {@code @SuppressWarnings} and no marker: the reference compiles
 * clean under {@code -Xlint:removal} because the class is deprecated without {@code forRemoval}. It is revisited
 * when the engine finishes moving armor to modifiers. If the class ever disappears from a server build, the
 * failure is LOUD and at boot, never silent wrong ordering: the class literal in the field initializer fails
 * to resolve while this filter is constructed (or the dependency's {@code validate()} throws if the name
 * resolves but no such system is registered), and {@code MobScalingPlugin}'s guarded registration of this one
 * system catches that, logs SEVERE naming the cause, and boots without the filter (scaled mobs then deal and
 * take ordinary damage for the session; everything else about them still works).
 *
 * <p>Running first has one consequence worth knowing when tuning: armor subtracts its flat amount after
 * the multiply, so flat resistance weighs more heavily against a scaled mob than it would if the multiply
 * came last (a raw 10 against flat 2 at a 0.45 scalar leaves 2.5 this way and 3.6 the other way), and this
 * is the order in which a small hit can be driven to nothing - see {@link #keepLandingHitsLanding}.
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
            // Scale the hit as rolled, before armor subtracts from it (see the class javadoc on what that costs).
            // ArmorDamageReduction is a deprecated engine class, referenced here as an ORDERING TARGET only
            // (a class literal, never a call); the class javadoc records why it stays and when it is revisited.
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
                observeBaseHit(store, attackerRef, amount);
                scaled *= attackerComp.result().outDmgMult(); // mob dealing damage
            }
            if (victimComp != null) {
                scaled *= victimComp.result().inDmgMult(); // mob taking damage (tankiness)
            }
            scaled = keepLandingHitsLanding(amount, scaled);
            if (scaled != amount) {
                damage.setAmount(scaled);
            }
        } catch (Throwable t) {
            safeWarn("damage filter failed: " + t);
        }
    }

    /**
     * Keep a hit that was landing from being scaled into nothing.
     *
     * <p>Damage reaches the health stat as {@code Math.round(amount)}, so anything under half a point
     * arrives as exactly zero while the swing itself still counts as a completed hit. A mob that takes
     * reduced damage can therefore end up immune to an entire weapon rather than merely tough: one
     * player kills it in the usual number of hits, another swings at it forever, the health bar never
     * moves, and nothing anywhere says why. So when the hit arriving here was already worth at least a
     * point, the hit leaving here is too.
     *
     * <p>This is a floor on what THIS mod's own multiply may do. The mob's armor and its resistance
     * effects are subtracted afterwards by the engine and can still take a hit to zero, which is
     * ordinary armor behaviour and is readable from the mob's own resistances.
     *
     * <p>The single point is the engine's rounding granularity rather than a balance figure, which is
     * why it is not a setting. To make a scaled mob take more or less damage, author
     * {@code Difficulty.StatCurve.VisibleHpShare} (how much of the toughness is silent damage reduction
     * rather than visible health) and {@code Difficulty.StatCurve.MaxEffectiveHpMult} (the rail on the
     * whole).
     */
    private static float keepLandingHitsLanding(float incoming, float scaled) {
        if (incoming >= 1f && scaled < 1f) {
            return 1f;
        }
        return Math.max(0f, scaled);
    }

    /**
     * Feed the admin page's preview the hit a scaled mob dealt as the engine rolled it: {@code amount}
     * here is the attacker's own number, before this mod's multiply and before armor, which is the
     * base the preview's "Hit" cell scales (see {@link RoleBaseHitResolver} for why it is observed and
     * never read off the role template). Display-only; a failed role read simply records nothing.
     */
    private static void observeBaseHit(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> attackerRef,
            float amount) {
        if (attackerRef == null) {
            return;
        }
        String roleName = EntityIdentifierUtil.roleName(store, attackerRef);
        if (roleName != null) {
            RoleBaseHitResolver.recordObserved(roleName, amount);
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
