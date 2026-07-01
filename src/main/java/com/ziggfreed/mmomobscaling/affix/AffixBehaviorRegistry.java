package com.ziggfreed.mmomobscaling.affix;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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

    static {
        registerBuiltins();
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
}
