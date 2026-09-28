package com.ziggfreed.mmomobscaling.pages;

import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

/**
 * DISPLAY-ONLY resolver for a role's base HIT, the damage-axis twin of {@link RoleBaseHealthResolver}:
 * it backs the absolute number beside the hit multiplier on the admin page's preview column. Never read
 * on a gameplay path; an absent reading only means the preview shows the multiplier alone.
 *
 * <p><b>Observed only, and deliberately so.</b> A role's health is ONE declared field
 * ({@code BuilderRole.maxHealth}), which is why the health resolver can also read it off the loaded role
 * template. A role's hit is not: the number lives inside whichever attack chain actually fires, which is
 * the held weapon's own interaction for an armed mob (the vanilla Skeleton draws a random weapon from a
 * loadout) and the role's {@code InteractionVars.Melee_Damage} calculator only for an unarmed one, and the
 * shared source marks that role-side number "not applicable" once a weapon overrides it. There is no
 * public, entity-free accessor for "the hit this role lands", and a template read of the role's own
 * calculator would be wrong for exactly the mobs an owner previews. So this resolver keeps only what the
 * damage filter SEES: {@link #recordObserved} is fed by {@code event.MobScalingDamageFilter} with the hit a
 * scaled mob dealt as the engine rolled it, before this mod's own multiply and before armor, per role. Last
 * write wins, so the figure is "the last hit a mob of this role landed", with the attack's own random
 * spread in it; it is labelled that way on the page.
 */
public final class RoleBaseHitResolver {

    /** {@code roleName -> the last pre-scale hit a mob of that role landed}; positive readings only. */
    private static final ConcurrentHashMap<String, Double> OBSERVED = new ConcurrentHashMap<>();

    private RoleBaseHitResolver() {
    }

    /**
     * Record {@code roleName}'s observed pre-scale hit from an actual damage event. Ignores a
     * non-positive reading so a bad sample can never poison the cache. Allocation-light, O(1); called
     * from the damage filter's hot path, which already sits inside its own guard.
     */
    public static void recordObserved(@Nonnull String roleName, double hit) {
        if (hit > 0.0) {
            OBSERVED.put(roleName, hit);
        }
    }

    /** The last observed pre-scale hit for {@code roleName}, or empty when no mob of that role has hit anything yet. */
    @Nonnull
    static OptionalDouble baseHit(@Nonnull String roleName) {
        Double observed = OBSERVED.get(roleName);
        return observed == null ? OptionalDouble.empty() : OptionalDouble.of(observed);
    }
}
