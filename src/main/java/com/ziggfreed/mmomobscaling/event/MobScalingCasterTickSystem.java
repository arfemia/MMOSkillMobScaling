package com.ziggfreed.mmomobscaling.event;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.caster.CasterCadence;
import com.ziggfreed.mmomobscaling.caster.CasterEntry;
import com.ziggfreed.mmomobscaling.caster.CasterFeatureState;
import com.ziggfreed.mmomobscaling.component.CasterKitComponent;
import com.ziggfreed.mmoskilltree.api.MMOSkillTreeAPI;

/**
 * The cadence caster: fires each armed {@link CasterKitComponent.Armed} entry when its due-timer
 * elapses, then reschedules it (see {@link CasterCadence}). Mirrors {@code MobScalingHudSystem}'s
 * short-circuit discipline, but goes one step further - the {@code Archetype} query itself excludes
 * every mob WITHOUT a {@link CasterKitComponent} (only a mob {@code MobScalingCasterArmSystem} actually
 * armed carries one), so the steady-state per-tick cost is proportional to armed mobs only, not every
 * scaled mob: a due-check is one {@code long} compare per armed entry, and an unchanged (not-yet-due)
 * entry does nothing else at all.
 *
 * <ul>
 *   <li>{@code ABILITY} entries call {@code MMOSkillTreeAPI.castNpcAbility(Store, Ref, String)} (the
 *       MMO's 1.6.0-cycle API). Any {@link LinkageError} (running against an older MMO jar that lacks
 *       the method) trips {@link CasterFeatureState#disableAbilityCasting} ONCE for the whole session -
 *       every subsequent tick then skips ability casts with a single {@code AtomicBoolean} read, so a
 *       mismatched jar pair degrades gracefully instead of spamming the log or crashing. An entry
 *       carrying an authored {@link CasterEntry.Windup} plays that wind-up animation via the engine's own
 *       {@code AnimationUtils.playAnimation} (entity-generic, callable directly - no MMO jar involved)
 *       immediately BEFORE the cast, same tick, so a scaled mob visibly telegraphs the incoming hit.</li>
 *   <li>{@code NATIVE_CHAIN} entries are NEVER scheduled here - {@code MobScalingCasterArmSystem} arms
 *       them onto the mob's native {@code CombatSupport} exactly once, at spawn, and deliberately does
 *       not add them to the tracked/ticked {@code CasterKitComponent} list, so this system's query never
 *       even sees one (see that class's javadoc for why a cadence re-arm would be actively harmful, not
 *       just redundant). A {@link CasterEntry.Windup} authored on a {@code NATIVE_CHAIN} entry is
 *       therefore inert (its own chain carries its own animation nodes) - {@code ScalingContentValidator}
 *       flags that authoring mistake; this system never plays it.</li>
 * </ul>
 */
public final class MobScalingCasterTickSystem extends EntityTickingSystem<EntityStore> {

    /** Warn-once-per-distinct-ability-id set for a cast the MMO API itself rejected (cooldown/cost/unknown id). */
    private static final Set<String> WARNED_REJECTED = ConcurrentHashMap.newKeySet();

    /** Warn-once-per-distinct-unknown-slot-name set for an authored {@code Windup.Slot} the engine doesn't know. */
    private static final Set<String> WARNED_UNKNOWN_SLOT = ConcurrentHashMap.newKeySet();

    @Nonnull
    private final ComponentType<EntityStore, CasterKitComponent> kitType = CasterKitComponent.getComponentType();

    @Nonnull
    private final Query<EntityStore> query = Query.and(kitType, NPCEntity.getComponentType());

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            CasterKitComponent kit = archetypeChunk.getComponent(index, kitType);
            if (kit == null || kit.armed().isEmpty()) {
                return;
            }
            Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
            if (ref == null || !ref.isValid()) {
                return;
            }
            long now = System.currentTimeMillis();
            for (CasterKitComponent.Armed armed : kit.armed()) {
                if (now < armed.nextCastAtMs()) {
                    continue;
                }
                CasterEntry entry = armed.entry();
                switch (entry.kind()) {
                    case ABILITY -> {
                        if (entry.windup() != null) {
                            playWindup(store, ref, entry.windup());
                        }
                        castAbility(store, ref, entry);
                    }
                    case NATIVE_CHAIN -> {
                        // Never reached: MobScalingCasterArmSystem arms a NATIVE_CHAIN entry once, at
                        // spawn, and deliberately never adds it to this kit's armed/tick list.
                        // addAttackOverride resets the native round-robin's attackOverrideIndex to 0 on
                        // EVERY call, so re-arming here on cadence would snap the cursor back to the
                        // first-armed chain on every tick, starving every other chain in a multi-chain
                        // roster - arm-once at spawn is both correct and sufficient (the round-robin
                        // never expires an entry on its own). Guarded no-op in case that invariant ever
                        // changes.
                    }
                    case INVALID -> {
                        // never armed by MobScalingCasterArmSystem (isEligible() excludes it); guard anyway
                    }
                }
                armed.setNextCastAtMs(CasterCadence.nextDueAt(now, entry.cadenceMs(), entry.jitterMs()));
            }
        } catch (Throwable t) {
            safeWarn("caster tick failed: " + t);
        }
    }

    /**
     * Cast one {@code ABILITY} entry through the frozen MMO API. A {@link LinkageError} (an older MMO
     * jar without {@code castNpcAbility}) disables ability casting for the whole session via
     * {@link CasterFeatureState}; any other throw is a per-attempt warning like every other system here.
     */
    private static void castAbility(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull CasterEntry entry) {
        if (CasterFeatureState.isAbilityCastingDisabled()) {
            return;
        }
        String abilityId = entry.abilityId();
        if (abilityId == null) {
            return; // guaranteed by isEligible()/Kind.ABILITY, but guard anyway
        }
        try {
            boolean cast = MMOSkillTreeAPI.castNpcAbility(store, ref, abilityId);
            if (!cast && WARNED_REJECTED.add(abilityId)) {
                safeWarn("caster ability '" + abilityId
                        + "' cast rejected by the MMO (unknown ability id, a passive/triggered ability "
                        + "that cannot be NPC-cast, or a step with no valid target) - will keep retrying "
                        + "on cadence; see the MMO log for the specific reason");
            }
        } catch (LinkageError e) {
            CasterFeatureState.disableAbilityCasting(String.valueOf(e));
        } catch (Throwable t) {
            safeWarn("caster ability '" + abilityId + "' cast failed: " + t);
        }
    }

    /**
     * Play one {@code ABILITY} entry's wind-up cue via the engine's own {@code AnimationUtils} - entity
     * generic, no MMO jar involved. A blank {@link CasterEntry.Windup#animation()} (a {@code Windup}
     * group present with no {@code Animation} authored) is a no-op here; {@code ScalingContentValidator}
     * flags that as content. Best-effort: any throw is a per-attempt warning, matching every other cast
     * path in this system.
     */
    private static void playWindup(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull CasterEntry.Windup windup) {
        String animation = windup.animation();
        if (animation.isBlank()) {
            return;
        }
        try {
            AnimationSlot slot = resolveSlot(windup);
            AnimationUtils.playAnimation(ref, slot, windup.itemAnimations(), animation, true, store);
        } catch (Throwable t) {
            safeWarn("caster windup animation '" + animation + "' failed: " + t);
        }
    }

    /**
     * An authored {@link CasterEntry.Windup#slot()} resolves by exact {@link AnimationSlot} name (a
     * per-distinct-unknown-value warn-once fallback to the documented default: {@code Action} for an
     * item-anim pair, {@code Status} for a model-level key). Absent/blank {@code Slot} goes straight to
     * that same default.
     */
    @Nonnull
    private static AnimationSlot resolveSlot(@Nonnull CasterEntry.Windup windup) {
        AnimationSlot fallback = windup.isItemAnim() ? AnimationSlot.Action : AnimationSlot.Status;
        String raw = windup.slot();
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return AnimationSlot.valueOf(raw);
        } catch (IllegalArgumentException e) {
            if (WARNED_UNKNOWN_SLOT.add(raw)) {
                safeWarn("caster windup unknown Slot '" + raw + "', falling back to " + fallback);
            }
            return fallback;
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
