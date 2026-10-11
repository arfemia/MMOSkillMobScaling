package com.ziggfreed.mmomobscaling.event;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.effect.grid.RuntimeEffectGrid;
import com.ziggfreed.common.instance.effect.EntityEffectService;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.component.ScaledMobComponent;
import com.ziggfreed.mmomobscaling.config.AffixConfig;
import com.ziggfreed.mmomobscaling.config.RarityConfig;
import com.ziggfreed.mmomobscaling.config.VariantConfig;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;
import com.ziggfreed.mmomobscaling.variant.Variant;

/**
 * The post-add companion to {@link MobScalingSpawnHook}: a {@link RefSystem} (query = the
 * {@code ScaledMobComponent} archetype) that fires with a VALID ref the same add cycle and RECONCILES the
 * native aura + affix EFFECTS to the CURRENT roll via the asset-authoritative {@code EntityEffectService.apply},
 * plus the mob's outgoing-damage multiplier as a runtime {@code OutgoingDamage} effect
 * ({@link OutgoingDamageEffect}).
 *
 * <p><b>Reconcile, not add-only.</b> It first RE-COMPUTES the desired {@code Mmoscaling_*} effect id set from
 * the fresh {@link MobScaleResult} (rarity aura + each {@code STAT}/{@code BEHAVIORAL} affix's own
 * {@code EffectId} + the outgoing-damage bucket when the multiplier rounds to a change), then:
 * <ol>
 *   <li>SWEEPS any active INFINITE effect whose id starts {@code Mmoscaling_} and is NOT desired
 *       (removeEffect) - so a rarity downgrade / retune / an excluded-mob cleanup never leaves a stale or
 *       double aura, and a re-roll at another damage percent never stacks two buckets. Timed effects are
 *       left alone (a legit victim-applied Freezing slow must survive).</li>
 *   <li>APPLIES each desired effect (idempotent by index, so a chunk-reload re-add is a no-op). The
 *       outgoing-damage bucket goes on through zc's {@link RuntimeEffectGrid}, which writes its asset the
 *       first time any mob needs it; a bucket still landing (written off the ticking world thread) re-runs
 *       this reconcile on the mob's world once it lands.</li>
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
            reconcile(ref, comp.result(), cb);
        } catch (Throwable t) {
            safeWarn("effect apply failed: " + t);
        }
    }

    /**
     * Reconcile the live entity's {@code Mmoscaling_*} effects to {@code result}: the sweep of any
     * stale infinite one, then the apply of each desired one. The body behind {@link #onEntityAdded},
     * and the call {@code MobScalingRollSystem} makes directly when it stamps a roll onto a LIVE
     * entity one tick after spawn (a component added to a live entity moves it between archetype
     * chunks without re-adding it, so no {@code RefSystem} sees that stamp). Takes either a
     * {@code CommandBuffer} (inside a system) or the {@code Store} (the landing retry, on a world task).
     */
    public static void reconcile(@Nonnull Ref<EntityStore> ref, @Nonnull MobScaleResult result,
            @Nonnull ComponentAccessor<EntityStore> accessor) {
        EffectControllerComponent ctrl = accessor.getComponent(ref, EffectControllerComponent.getComponentType());
        if (ctrl == null) {
            return;
        }
        Set<String> desired = desiredEffectIds(result);
        RuntimeEffectGrid.Bucket outDamage = OutgoingDamageEffect.bucketFor(result);
        Set<String> keep = new HashSet<>(desired);
        if (outDamage != null) {
            keep.add(OutgoingDamageEffect.effectId(outDamage));
        }
        sweepStale(ref, ctrl, accessor, keep); // remove stale Mmoscaling_* infinite effects BEFORE re-applying
        for (String effectId : desired) {
            apply(ref, accessor, effectId);
        }
        if (outDamage != null) {
            applyOutDamage(ref, accessor, outDamage);
        }
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> cb) {
        // No-op: DeathSystems.ClearEntityEffects clears our infinite effects on death.
    }

    /**
     * The Mmoscaling_ effect ids this roll WANTS: the rarity aura (or, when the rarity has none, the VARIANT's
     * aura as a fallback - the rarity always wins the single body-tint channel, so the two never fight) + each
     * STAT/BEHAVIORAL affix's self effect. The outgoing-damage bucket is not here: it is generated, so it
     * goes on through the grid rather than {@code EntityEffectService}.
     */
    @Nonnull
    private static Set<String> desiredEffectIds(@Nonnull MobScaleResult r) {
        Set<String> desired = new HashSet<>();
        boolean rarityAura = false;
        if (r.hasRarity()) {
            Rarity rarity = RarityConfig.getInstance().resolve(r.rarityId());
            if (rarity != null && rarity.auraEffectId() != null) {
                desired.add(rarity.auraEffectId());
                rarityAura = true;
            }
        }
        // Variant aura is a FALLBACK tint: applied only when the base rarity contributed no aura (a plain-base
        // variant mob, e.g. a "Horrific Spider"), so a variant riding a rarity keeps the rarity's tint.
        if (!rarityAura && r.hasVariant()) {
            Variant variant = VariantConfig.getInstance().resolve(r.variantId());
            if (variant != null && variant.auraEffectId() != null) {
                desired.add(variant.auraEffectId());
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
            @Nonnull ComponentAccessor<EntityStore> accessor) {
        return sweepStale(ref, ctrl, accessor, Set.of());
    }

    /**
     * Remove any active INFINITE {@code Mmoscaling_*} effect not in {@code desired} (stale aura / affix /
     * outgoing-damage bucket).
     *
     * @return true when at least one effect was removed
     */
    private static boolean sweepStale(@Nonnull Ref<EntityStore> ref, @Nonnull EffectControllerComponent ctrl,
            @Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Set<String> desired) {
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
                ctrl.removeEffect(ref, idx, accessor);
                removedAny = true;
            }
        }
        return removedAny;
    }

    /** Asset-authoritative apply (idempotent by index); warn-once on an unresolved id. */
    private static void apply(@Nonnull Ref<EntityStore> ref, @Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull String effectId) {
        if (!applyAsset(ref, accessor, effectId) && WARNED_IDS.add(effectId)) {
            safeWarn("scaled-mob effect not applied (unregistered id or engine-rejected): " + effectId);
        }
    }

    /** {@code EntityEffectService.apply} over whichever accessor the reconcile was handed. */
    private static boolean applyAsset(@Nonnull Ref<EntityStore> ref, @Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull String effectId) {
        if (accessor instanceof CommandBuffer<EntityStore> cb) {
            return EntityEffectService.apply(ref, effectId, cb);
        }
        if (accessor instanceof Store<EntityStore> store) {
            return EntityEffectService.apply(ref, effectId, store);
        }
        return false;
    }

    /**
     * Put the outgoing-damage bucket on through the grid (idempotent: the engine keeps one entry per
     * effect index). A bucket whose asset is still landing re-runs the whole reconcile on the mob's world
     * once it lands; a failure warns once per id (the grid has already logged a failed write).
     */
    private static void applyOutDamage(@Nonnull Ref<EntityStore> ref, @Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull RuntimeEffectGrid.Bucket bucket) {
        RuntimeEffectGrid.Outcome outcome = OutgoingDamageEffect.GRID.applyInfinite(accessor, ref, bucket,
                retryWhenLanded(ref, accessor));
        String effectId = OutgoingDamageEffect.effectId(bucket);
        if (outcome == RuntimeEffectGrid.Outcome.FAILED && WARNED_IDS.add(effectId)) {
            safeWarn("scaled-mob outgoing-damage effect not applied (write failed or engine-rejected): " + effectId);
        }
    }

    /**
     * The grid's {@code onLanded}: hop back onto the mob's world (a landing completes on the writer
     * thread) and reconcile again there, or {@code null} when the accessor names no world.
     */
    @Nullable
    private static Runnable retryWhenLanded(@Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> accessor) {
        EntityStore entityStore = accessor.getExternalData();
        World world = entityStore != null ? entityStore.getWorld() : null;
        return world == null ? null : RuntimeEffectGrid.onWorldThread(world, () -> reconcileLanded(ref));
    }

    /**
     * The landing retry, on the mob's world thread outside any system: reconcile again through the
     * {@code Store} if the mob is still there and still scaled, reading its CURRENT roll (a re-roll in
     * the meantime wins). Never throws.
     */
    private static void reconcileLanded(@Nonnull Ref<EntityStore> ref) {
        try {
            if (!ref.isValid()) {
                return;
            }
            Store<EntityStore> store = ref.getStore();
            ScaledMobComponent comp = store.getComponent(ref, ScaledMobComponent.getComponentType());
            if (comp != null) {
                reconcile(ref, comp.result(), store);
            }
        } catch (Throwable t) {
            safeWarn("effect re-apply after the outgoing-damage asset landed failed: " + t);
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
