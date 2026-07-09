package com.ziggfreed.mmomobscaling.affix;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A mod-side BEHAVIORAL affix descriptor: the per-hit policy the native engine has no data hook for. Per the
 * native-leverage audit, only genuinely per-hit-DEALT behavior stays mod-side (Vampiric lifesteal has no
 * native on-hit sensor; the Freezing on-hit TRIGGER is mod-side even though the slow it applies IS a native
 * {@code EntityEffect}). Everything data-shaped (Armored/Stalwart/Swift) is a pure native effect and needs
 * NO behavior.
 *
 * <p>This is a plain descriptor (id + kind + params), resolved by {@link AffixBehaviorRegistry}. The LIFESTEAL
 * kind dispatches through {@code com.ziggfreed.common.cast.OnHitRegistry} (the shared cast on-hit registry
 * lifted into ziggfreed-common; this mod is its proving second consumer), which builds a per-hit heal
 * consumer from a type+params payload. The APPLY_EFFECT_ON_HIT kind applies the {@link #effectId} effect
 * asset directly to the victim via {@code EntityEffectService}, since that call needs a
 * {@code CommandBuffer} accessor the on-hit consumer shape does not carry.
 */
public final class AffixBehavior {

    /** Behavior kinds. LIFESTEAL heals the attacker by a fraction of damage dealt; APPLY_EFFECT_ON_HIT applies a native effect to the victim. */
    public enum Kind {
        LIFESTEAL,
        APPLY_EFFECT_ON_HIT
    }

    private final String id;
    private final Kind kind;
    private final double magnitude;
    @Nullable private final String effectId;

    public AffixBehavior(@Nonnull String id, @Nonnull Kind kind, double magnitude, @Nullable String effectId) {
        this.id = id;
        this.kind = kind;
        this.magnitude = magnitude;
        this.effectId = effectId;
    }

    @Nonnull public String id() { return id; }
    @Nonnull public Kind kind() { return kind; }

    /** LIFESTEAL: the fraction of dealt damage healed back to the attacker. Unused by other kinds. */
    public double magnitude() { return magnitude; }

    /** APPLY_EFFECT_ON_HIT: the native {@code EntityEffect} id applied to the victim on hit. Null otherwise. */
    @Nullable public String effectId() { return effectId; }
}
