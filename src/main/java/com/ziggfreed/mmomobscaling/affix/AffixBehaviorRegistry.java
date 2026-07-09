package com.ziggfreed.mmomobscaling.affix;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.cast.CastParams;
import com.ziggfreed.common.cast.OnHitRegistry;
import com.ziggfreed.common.health.HealthUtil;

/**
 * The registry of mod-side {@link AffixBehavior}s, keyed by lowercase behavior id (the affix's
 * {@code BehaviorId}). The two MVP builtins register at class init; a new behavioral affix is one
 * {@link #register} call. The LIVE per-hit dispatch (reading these off a scaled mob in the damage filter)
 * lands in Phase 5.
 *
 * <p>Behavioral params are mod POLICY (the audit keeps Vampiric/Freezing on-hit logic mod-side), so the
 * lifesteal fraction + slow effect id live here, not in pack JSON; a future enhancement can promote them to
 * asset fields if pack authors need to tune them.
 */
public final class AffixBehaviorRegistry {

    private static final Map<String, AffixBehavior> REGISTRY = new ConcurrentHashMap<>();

    /**
     * The lifted cast on-hit registry (ziggfreed-common 1.4.0's {@code cast.OnHitRegistry}); this mod
     * is its proving second consumer. Only the Vampiric lifesteal kind is registered here - it is the
     * one behavioral affix shaped like a generic on-hit payload (a type + params -> a per-hit consumer).
     */
    private static final OnHitRegistry ON_HIT = new OnHitRegistry();

    static {
        registerBuiltins();
        // Lifesteal: routes the Vampiric behavioral affix's per-hit heal through the shared cast
        // on-hit registry as its proving second consumer. amount<=0 or no attacker to heal -> NO_OP,
        // reproducing the old inline "heal > 0f" gate exactly.
        ON_HIT.register("LIFESTEAL", (params, sourceRef, sourcePlayerId) -> {
            float amount = (float) CastParams.numberOr(params, "amount", 0.0).doubleValue();
            if (amount <= 0f || sourceRef == null) {
                return OnHitRegistry.NO_OP;
            }
            return (store, victim) -> HealthUtil.heal(store, sourceRef, amount);
        });
    }

    private AffixBehaviorRegistry() {
    }

    private static void registerBuiltins() {
        // Vampiric: heal the attacker for 2% of damage dealt (no native on-hit-DEALT sensor -> mod-side).
        register(new AffixBehavior("vampiric", AffixBehavior.Kind.LIFESTEAL, 0.02, null));
        // Freezing: on a hit dealt, apply the native slow EntityEffect to the victim (the slow itself is pure data).
        register(new AffixBehavior("freezing", AffixBehavior.Kind.APPLY_EFFECT_ON_HIT, 0.0, "Mmoscaling_Affix_Freezing_Slow"));
    }

    /** Register (or replace) a behavior; keyed by lowercase id. */
    public static void register(@Nonnull AffixBehavior behavior) {
        REGISTRY.put(behavior.id().toLowerCase(Locale.ROOT), behavior);
    }

    /** Look up a behavior by id (case-insensitive); {@code null} if unregistered or {@code id} is null. */
    @Nullable
    public static AffixBehavior get(@Nullable String id) {
        return id == null ? null : REGISTRY.get(id.toLowerCase(Locale.ROOT));
    }

    /** True when a behavior is registered for the id. */
    public static boolean has(@Nullable String id) {
        return get(id) != null;
    }

    /**
     * Builds the per-hit lifesteal consumer for the Vampiric behavioral affix by routing {@code amount}
     * through the lifted cast on-hit registry (this mod's proving second consumer of
     * {@code com.ziggfreed.common.cast.OnHitRegistry}). Returns {@link OnHitRegistry#NO_OP} when
     * {@code amount <= 0} or {@code attackerRef} is {@code null} (nothing to heal); otherwise the
     * returned consumer heals {@code attackerRef} by {@code amount} via {@link HealthUtil#heal}.
     */
    @Nonnull
    public static BiConsumer<Store<EntityStore>, Ref<EntityStore>> lifestealOnHit(
            double amount, @Nullable Ref<EntityStore> attackerRef) {
        return ON_HIT.fromParams(Map.of("type", "LIFESTEAL", "amount", amount), attackerRef, null);
    }
}
