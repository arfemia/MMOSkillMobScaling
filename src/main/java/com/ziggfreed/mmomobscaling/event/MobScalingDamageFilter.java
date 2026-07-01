package com.ziggfreed.mmomobscaling.event;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.health.HealthUtil;
import com.ziggfreed.common.instance.effect.EntityEffectService;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.affix.AffixBehavior;
import com.ziggfreed.mmomobscaling.affix.AffixBehaviorRegistry;
import com.ziggfreed.mmomobscaling.component.ScaledMobComponent;
import com.ziggfreed.mmomobscaling.config.AffixConfig;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;

/**
 * The per-hit filter: a {@link DamageEventSystem} in {@code DamageModule.getFilterDamageGroup()} (so it sees
 * ALL damage), reading the FROZEN {@code ScaledMobComponent} mults - zero affix walk on the common path.
 * A scaled ATTACKER scales its OUTGOING damage by {@code outDmgMult}; a scaled VICTIM scales INCOMING damage
 * by {@code inDmgMult}. Both directions are handled by checking attacker + victim independently.
 *
 * <p>Behavioral affixes fire only when a scaled attacker HAS affixes (rare): Vampiric heals the attacker for
 * a fraction of the dealt damage (no native on-hit-DEALT sensor -> mod-side), and Freezing applies its native
 * slow {@code EntityEffect} to the victim (the slow itself is pure data). STAT affixes are native self-effects
 * and are skipped here. Whole body try-guarded; never initiates damage.
 */
public final class MobScalingDamageFilter extends DamageEventSystem {

    /** The native slow's on-hit duration (seconds); the Freezing effect asset also carries this. */
    private static final float FREEZING_SECONDS = 3f;

    @Nonnull
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
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

            // Behavioral affixes fire when the ATTACKER is a scaled mob carrying affixes.
            if (attackerComp != null && attackerComp.result().hasAffixes()
                    && victimRef != null && victimRef.isValid()) {
                applyBehavioral(store, cb, attackerRef, victimRef, attackerComp.result(), scaled);
            }
        } catch (Throwable t) {
            safeWarn("damage filter failed: " + t);
        }
    }

    private static void applyBehavioral(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> cb,
            @Nonnull Ref<EntityStore> attackerRef, @Nonnull Ref<EntityStore> victimRef,
            @Nonnull MobScaleResult result, float dealt) {
        for (String affixId : result.affixIds()) {
            Affix affix = AffixConfig.getInstance().resolve(affixId);
            if (affix == null || Affix.KIND_STAT.equals(affix.kind())) {
                continue; // stat affixes are native self-effects, not per-hit behavior
            }
            AffixBehavior behavior = AffixBehaviorRegistry.get(affix.behaviorId());
            if (behavior == null) {
                continue;
            }
            switch (behavior.kind()) {
                case LIFESTEAL -> {
                    float heal = (float) (dealt * behavior.magnitude());
                    if (heal > 0f) {
                        HealthUtil.heal(store, attackerRef, heal);
                    }
                }
                case APPLY_EFFECT_ON_HIT -> {
                    String effectId = behavior.effectId();
                    if (effectId != null) {
                        EntityEffectService.applyTimed(victimRef, effectId, FREEZING_SECONDS, OverlapBehavior.OVERWRITE, cb);
                    }
                }
            }
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
