package com.ziggfreed.mmomobscaling.event;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.instance.effect.EntityEffectService;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.component.ScaledMobComponent;
import com.ziggfreed.mmomobscaling.config.AffixConfig;
import com.ziggfreed.mmomobscaling.config.RarityConfig;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;

/**
 * The post-add companion to {@link MobScalingSpawnHook}: a {@link RefSystem} (query = the
 * {@code ScaledMobComponent} archetype) that fires with a VALID ref the same add cycle and RECONCILES the
 * native aura + affix EFFECTS to the CURRENT roll via the asset-authoritative {@code EntityEffectService.apply}.
 *
 * <p><b>Reconcile, not add-only.</b> It first RE-COMPUTES the desired {@code Mmoscaling_*} effect id set from
 * the fresh {@link MobScaleResult} (rarity aura + each {@code STAT}/{@code BEHAVIORAL} affix's own
 * {@code EffectId}), then:
 * <ol>
 *   <li>SWEEPS any active INFINITE effect whose id starts {@code Mmoscaling_} and is NOT desired
 *       (removeEffect) - so a rarity downgrade / retune / an excluded-mob cleanup never leaves a stale or
 *       double aura. Timed effects are left alone (a legit victim-applied Freezing slow must survive).</li>
 *   <li>APPLIES each desired effect (idempotent by index, so a chunk-reload re-add is a no-op).</li>
 * </ol>
 *
 * <p>HYBRID affixes (Freezing) are skipped for self-apply - their {@code EffectId} is the on-hit VICTIM slow
 * applied by {@link MobScalingOnHitSystem}, not a self effect. Whole body try-guarded.
 */
public final class MobScalingEffectApplySystem extends RefSystem<EntityStore> {

    /** Every mod-applied effect id carries this prefix; the sweep only ever removes ids under it. */
    static final String EFFECT_PREFIX = "Mmoscaling_";

    /** Warn-once-per-distinct-id set, so a busy spawner with one typo'd id does not warn every spawn. */
    private static final Set<String> WARNED_IDS = ConcurrentHashMap.newKeySet();

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

            Set<String> desired = desiredEffectIds(r);
            sweepStale(ref, ctrl, cb, desired); // remove stale Mmoscaling_* infinite effects BEFORE re-applying
            for (String effectId : desired) {
                apply(ref, cb, effectId);
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

    /** The Mmoscaling_ effect ids this roll WANTS: the rarity aura + each STAT/BEHAVIORAL affix's self effect. */
    @Nonnull
    private static Set<String> desiredEffectIds(@Nonnull MobScaleResult r) {
        Set<String> desired = new HashSet<>();
        if (r.hasRarity()) {
            Rarity rarity = RarityConfig.getInstance().resolve(r.rarityId());
            if (rarity != null && rarity.auraEffectId() != null) {
                desired.add(rarity.auraEffectId());
            }
        }
        for (String affixId : r.affixIds()) {
            Affix affix = AffixConfig.getInstance().resolve(affixId);
            // STAT affixes self-apply their native effect (Armored DamageResistance, Swift speed, Stalwart
            // knockback immunity). BEHAVIORAL (Vampiric) has no self effect; HYBRID (Freezing) is victim-directed.
            if (affix != null && affix.effectId() != null && Affix.KIND_STAT.equals(affix.kind())) {
                desired.add(affix.effectId());
            }
        }
        return desired;
    }

    /** Remove EVERY active INFINITE {@code Mmoscaling_*} effect (the {@code /mobscaling purge} sweep). */
    public static boolean sweepAll(@Nonnull Ref<EntityStore> ref, @Nonnull EffectControllerComponent ctrl,
            @Nonnull CommandBuffer<EntityStore> cb) {
        return sweepStale(ref, ctrl, cb, Set.of());
    }

    /**
     * Remove any active INFINITE {@code Mmoscaling_*} effect not in {@code desired} (stale aura / affix).
     *
     * @return true when at least one effect was removed
     */
    private static boolean sweepStale(@Nonnull Ref<EntityStore> ref, @Nonnull EffectControllerComponent ctrl,
            @Nonnull CommandBuffer<EntityStore> cb, @Nonnull Set<String> desired) {
        ActiveEntityEffect[] active = ctrl.getAllActiveEntityEffects();
        if (active == null) {
            return false;
        }
        boolean removedAny = false;
        for (ActiveEntityEffect ae : active) {
            if (ae == null || !ae.isInfinite()) {
                continue; // leave timed effects (a victim-applied Freezing slow) alone
            }
            int idx = ae.getEntityEffectIndex();
            EntityEffect asset = EntityEffect.getAssetMap().getAsset(idx);
            if (asset == null) {
                continue;
            }
            String id = asset.getId();
            if (id != null && id.startsWith(EFFECT_PREFIX) && !desired.contains(id)) {
                ctrl.removeEffect(ref, idx, cb);
                removedAny = true;
            }
        }
        return removedAny;
    }

    /** Asset-authoritative apply (idempotent by index); warn-once on an unresolved id. */
    private static void apply(@Nonnull Ref<EntityStore> ref, @Nonnull CommandBuffer<EntityStore> cb,
            @Nonnull String effectId) {
        if (!EntityEffectService.apply(ref, effectId, cb) && WARNED_IDS.add(effectId)) {
            safeWarn("scaled-mob effect not applied (unregistered id or engine-rejected): " + effectId);
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
