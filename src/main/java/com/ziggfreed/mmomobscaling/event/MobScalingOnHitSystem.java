package com.ziggfreed.mmomobscaling.event;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.instance.effect.EntityEffectService;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.affix.AffixBehavior;
import com.ziggfreed.mmomobscaling.affix.AffixBehaviorRegistry;
import com.ziggfreed.mmomobscaling.component.ScaledMobComponent;
import com.ziggfreed.mmomobscaling.config.AffixConfig;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;

/**
 * The behavioral-affix reactions: a {@link DamageEventSystem} in {@code DamageModule.getInspectDamageGroup()},
 * which runs AFTER {@code DamageSystems.ApplyDamage} (per its BEFORE-inspect group dependency). So
 * {@code damage.getAmount()} here is the FINAL applied value - post-armor, post-MMO-defense, post int-round,
 * and a lethal/blocked hit is already {@code isCancelled()}. Fires only when a scaled ATTACKER carries affixes:
 *
 * <ul>
 *   <li>Vampiric (LIFESTEAL): heal the attacker for a fraction of the damage ACTUALLY dealt (not the
 *       pre-mitigation "scaled" amount the multiply filter computed).</li>
 *   <li>Freezing (APPLY_EFFECT_ON_HIT): apply the affix's own {@code EntityEffect} (authored {@code EffectId})
 *       to the victim via the ASSET-AUTHORITATIVE {@code EntityEffectService.apply} - the effect's own
 *       {@code Duration} + {@code OverlapBehavior} govern (no Java-baked seconds/overwrite).</li>
 * </ul>
 *
 * Guards: skip cancelled / non-positive hits (so Freezing does not slow a god-mode / fully-blocked target and
 * Vampiric does not heal off a 0-damage hit) and self-hits. Whole body try-guarded; never initiates damage.
 */
public final class MobScalingOnHitSystem extends DamageEventSystem {

    @Nonnull
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getInspectDamageGroup();
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
            float dealt = damage.getAmount(); // FINAL applied damage (this group runs after ApplyDamage)
            if (dealt <= 0f) {
                return;
            }

            Ref<EntityStore> attackerRef = attackerOf(damage);
            if (attackerRef == null || !attackerRef.isValid()) {
                return;
            }
            Ref<EntityStore> victimRef = chunk.getReferenceTo(index);
            if (victimRef == null || !victimRef.isValid() || attackerRef == victimRef) {
                return; // no self-behavioral
            }
            ScaledMobComponent attackerComp = store.getComponent(attackerRef, ScaledMobComponent.getComponentType());
            if (attackerComp == null || !attackerComp.result().hasAffixes()) {
                return;
            }
            applyBehavioral(store, cb, attackerRef, victimRef, attackerComp.result(), dealt);
        } catch (Throwable t) {
            safeWarn("on-hit affix failed: " + t);
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
                case LIFESTEAL ->
                        AffixBehaviorRegistry.lifestealOnHit(dealt * behavior.magnitude(), attackerRef)
                                .accept(store, victimRef);
                case APPLY_EFFECT_ON_HIT -> {
                    // Effect-on-hit stays a direct call (not routed through the cast on-hit registry): the
                    // registry's BiConsumer<Store,Ref> shape carries no CommandBuffer accessor, and
                    // EntityEffectService.apply needs the CommandBuffer (Phase B API-shape finding).
                    // Prefer the affix asset's authored EffectId (was DEAD for HYBRID) over the registry copy,
                    // and let the effect asset's own Duration + OverlapBehavior govern (asset-authoritative).
                    String effectId = affix.effectId() != null ? affix.effectId() : behavior.effectId();
                    if (effectId != null && !EntityEffectService.apply(victimRef, effectId, cb)) {
                        safeWarn("scaled-mob on-hit effect did not apply (unregistered id or engine-rejected): "
                                + effectId);
                    }
                }
            }
        }
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
