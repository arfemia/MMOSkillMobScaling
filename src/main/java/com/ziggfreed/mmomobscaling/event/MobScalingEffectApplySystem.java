package com.ziggfreed.mmomobscaling.event;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.component.ScaledMobComponent;
import com.ziggfreed.mmomobscaling.config.AffixConfig;
import com.ziggfreed.mmomobscaling.config.RarityConfig;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;

/**
 * The post-add companion to {@link MobScalingSpawnHook}: a {@link RefSystem} (query = the
 * {@code ScaledMobComponent} archetype) that fires with a VALID ref the same add cycle and applies the
 * native aura + affix EFFECTS via {@code EffectControllerComponent.addInfiniteEffect} - the native-leverage
 * audit's RefSystem-not-{@code world.execute} apply (rank 4).
 *
 * <p>Applies to SELF: the rarity aura ({@code AuraEffectId}) + each {@code STAT} affix's native
 * {@code EffectId} (Armored {@code DamageResistance}, Swift {@code HorizontalSpeedMultiplier}, Stalwart
 * visual). Skips {@code BEHAVIORAL} (no effect) and {@code HYBRID} (Freezing's slow is applied to the VICTIM
 * on-hit by the damage filter, not to self). {@code addInfiniteEffect} is keyed by effect index, so a chunk
 * reload re-add is idempotent. Whole body try-guarded.
 */
public final class MobScalingEffectApplySystem extends RefSystem<EntityStore> {

    @Nonnull
    private final ComponentType<EntityStore, ScaledMobComponent> scaledType = ScaledMobComponent.getComponentType();

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(scaledType);

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> cb) {
        try {
            if (!ref.isValid()) {
                return;
            }
            ScaledMobComponent comp = store.getComponent(ref, scaledType);
            if (comp == null) {
                return;
            }
            MobScaleResult r = comp.result();
            EffectControllerComponent ctrl = cb.getComponent(ref, EffectControllerComponent.getComponentType());
            if (ctrl == null) {
                return;
            }
            // Rarity aura (ModelVFX / particles / tints; boss adds KnockbackMultiplier).
            if (r.hasRarity()) {
                Rarity rarity = RarityConfig.getInstance().resolve(r.rarityId());
                if (rarity != null && rarity.auraEffectId() != null) {
                    applyInfinite(ref, ctrl, cb, rarity.auraEffectId());
                }
            }
            // STAT affix self-effects only.
            for (String affixId : r.affixIds()) {
                Affix affix = AffixConfig.getInstance().resolve(affixId);
                if (affix != null && Affix.KIND_STAT.equals(affix.kind()) && affix.effectId() != null) {
                    applyInfinite(ref, ctrl, cb, affix.effectId());
                }
            }
        } catch (Throwable t) {
            safeWarn("effect apply failed: " + t);
        }
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> cb) {
        // No-op: DeathSystems.ClearEntityEffects clears our infinite effects on death.
    }

    private static void applyInfinite(@Nonnull Ref<EntityStore> ref, @Nonnull EffectControllerComponent ctrl,
            @Nonnull CommandBuffer<EntityStore> cb, @Nonnull String effectId) {
        int idx = EntityEffect.getAssetMap().getIndex(effectId);
        if (idx == Integer.MIN_VALUE) {
            return; // effect asset not present (validated in-game)
        }
        EntityEffect fx = EntityEffect.getAssetMap().getAsset(idx);
        if (fx != null) {
            ctrl.addInfiniteEffect(ref, idx, fx, cb);
        }
    }

    private static void safeWarn(@Nonnull String message) {
        try {
            MobScalingPlugin.LOGGER.atWarning().log(message);
        } catch (Throwable ignored) {
            // log-manager-less JVMs
        }
    }
}
