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
 * The per-hit INCOMING damage multiply: a {@link DamageEventSystem} in {@code DamageModule.getFilterDamageGroup()}
 * (so it sees all damage) that scales a hit on a scaled VICTIM by the frozen {@code inDmgMult} on its
 * {@code ScaledMobComponent}, with zero affix walk. It also feeds the admin preview the hit a scaled ATTACKER
 * rolled ({@link #observeBaseHit}).
 *
 * <p><b>The split: outgoing is native, incoming is here.</b> A scaled mob's OUTGOING multiply
 * ({@code outDmgMult}) is a native {@code OutgoingDamage} effect the roll's effect reconcile puts on the mob
 * ({@link OutgoingDamageEffect}, one shared runtime asset per percent from ziggfreed-common's effect grid),
 * keyed on the three root damage types, which the engine's own outgoing step applies in this group after
 * armor. The INCOMING side stays in Java because the engine's inbound lookup matches a hit's exact type, so a
 * native body would have to key every type there is, vanilla's and every pack's.
 *
 * <p><b>Ordering ({@link #getDependencies}).</b> Pinned BEFORE the MMO's {@code CombatDamageEventSystem}
 * (its crit and defense math, the other Filter-group modifier) and BEFORE the vanilla
 * {@code ArmorDamageReduction}, so both the incoming multiply and the preview's observation see the hit as
 * the attacker rolled it. Armor then subtracts its flat amount after the multiply, so flat resistance weighs
 * more against a tough scaled mob than it would if the multiply came last (a raw 10 against flat 2 at a 0.45
 * scalar leaves 2.5 this way and 3.6 the other way); {@link #keepLandingHitsLanding} keeps the multiply itself
 * from scaling a landing hit to nothing. The MMO edge named {@code CombatXpEventSystem} until 1.1.0, when that
 * system moved to the Inspect group; it targets the Filter-group peer so a removed class never throws at
 * {@code SystemDependency.validate()}.
 *
 * <p><b>The {@code DamageSystems.ArmorDamageReduction} class literal is a DECISION, not an oversight
 * (maintainer ruling, 2026-09-28: it stays).</b> The engine marks the class deprecated ("Move to modifiers")
 * and names no replacement. Here it is only an ordering target inside a {@code SystemDependency}, never a call,
 * and there is no other handle on the armor step: the engine's {@code DependencyGraph} admits an edge by class,
 * group or type, and a group or type edge would land on every Filter-group peer rather than on the armor step
 * alone. So it stays with the build's {@code // DEPRECATION-KEPT} marker and no {@code @SuppressWarnings} (the
 * class is deprecated without {@code forRemoval}), revisited when the engine finishes moving armor to
 * modifiers. If the class ever leaves a server build the failure is LOUD at boot, never silent wrong ordering:
 * constructing this filter (or the dependency's {@code validate()}) throws, and {@code MobScalingPlugin}'s
 * guarded registration logs SEVERE and boots without it (scaled mobs then take ordinary damage for the
 * session; their own hits still scale through the effect).
 *
 * <p><b>Behavioral affixes are NOT here.</b> Vampiric lifesteal and the Freezing on-hit slow live in
 * {@link MobScalingOnHitSystem} (the INSPECT group), where {@code damage.getAmount()} is the FINAL applied value
 * (post-armor, post-MMO-defense, post-rounding, dead-victim already cancelled). Whole body try-guarded; never
 * initiates damage.
 */
public final class MobScalingDamageFilter extends DamageEventSystem {

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            // Filter-phase peer ordering: our scaling multiply lands before the MMO's own crit/defense math.
            new SystemDependency<>(Order.BEFORE, CombatDamageEventSystem.class),
            // Scale the hit as rolled, before armor subtracts from it (see the class javadoc on what that costs).
            // A class literal used as an ORDERING TARGET only, never a call; the class javadoc records why it
            // stays and when it is revisited.
            // DEPRECATION-KEPT: no replacement system exists for the armor step, and this filter must run before it
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

            // A scaled attacker's hit as rolled, for the admin preview. A self-hit (a scaled mob caught in its
            // own AoE) is not one of its attacks, so it is not observed. Identity compare is correct (Ref does
            // not override equals; the chunk + engine source pass the canonical stored Ref instance).
            if (attackerRef != victimRef && validComp(store, attackerRef) != null) {
                observeBaseHit(store, attackerRef, amount);
            }

            if (victimComp == null) {
                return; // the victim is not a scaled mob: nothing to multiply here
            }
            float scaled = keepLandingHitsLanding(amount, amount * victimComp.result().inDmgMult());
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
     * <p>This is a floor on what this filter's incoming multiply may do. The mob's armor and its
     * resistance effects are subtracted afterwards by the engine and can still take a hit to zero, which
     * is ordinary armor behaviour and is readable from the mob's own resistances. The outgoing multiply is
     * the engine's own effect step, after armor, and carries no such floor.
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
     * here is the attacker's own number, before its outgoing-damage effect and before armor, which is
     * the base the preview's "Hit" cell scales (see {@link RoleBaseHitResolver} for why it is observed
     * and never read off the role template). Display-only; a failed role read simply records nothing.
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
