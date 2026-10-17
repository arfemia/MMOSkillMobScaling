package com.ziggfreed.mmomobscaling.event;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import com.ziggfreed.common.encounter.event.EncounterEngagedEvent;
import com.ziggfreed.common.encounter.event.EncounterResetEvent;
import com.ziggfreed.common.encounter.run.EncounterRuntime;
import com.ziggfreed.common.health.HealthUtil;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.component.ScaledMobComponent;
import com.ziggfreed.mmomobscaling.config.MobScalingConfig;
import com.ziggfreed.mmomobscaling.config.SpawnScalingSettings;
import com.ziggfreed.mmomobscaling.scaling.MobScaleFold;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;

/**
 * This mod's half of an encounter binding row's {@code Scale.Companion}: a fight whose row lets a companion
 * scale its boss gets him scaled with the same level his adds read where he stands, once per fight, at the
 * engage. Every other encounter boss stays exactly as the roll's two skips leave him.
 *
 * <p><b>When.</b> On CommonLib's {@link EncounterEngagedEvent}, fired once per run when the fight is on: by
 * then the script has bound him, put on its per-player health steps and refilled him, so he is full. The
 * event fires inside the encounter's own systems, where a structural write is refused, so the scale lands on
 * the world's next task. A wipe resets the run and the next engage reads the level again; between the two
 * the level is never read again ({@link #BY_RUN}), a reload included.
 *
 * <p><b>What.</b> {@link MobScaleFold#anchored}: the plain fold (no rarity, no Boss multiplier, no affix, no
 * aura, no caster kit, no rename), each axis divided by its value at the spot's floor, so at the floor he is
 * what his role and his fight make him. {@code Health} writes the {@code mmoscaling_hp} multiplying modifier
 * as a first write, which fills the bar to its new top; {@code Damage} puts on the native outgoing-damage
 * effect. The engine folds a stat's additive modifiers (his role's maximum, the fight's per-player steps)
 * before its multiplying ones, so the multiplier lands on base plus steps, and the fight's own steps count
 * the party once while this counts the level once. The stamp is a {@link ScaledMobComponent#ofCompanion}
 * one, so kill XP and loot stay as they are (plain, 1.0) and the caster arm leaves him alone.
 *
 * <p><b>The throw to his feet.</b> An in-place role change re-adds the same holder: the stat map keeps the
 * modifier, the effect controller keeps the effect, and the spawn hook sees the stamp and never rolls him;
 * only the role's own maximum ({@code NPC_Max}) is replaced, and it is additive, under the multiplier.
 *
 * <p><b>A reload mid-fight.</b> His stamp is transient, so a reloaded boss comes back through the roll's
 * skips; for an engaged fight that opts in they call {@link #rescaleReloaded} instead of stripping him, and
 * he takes back the fight's own scale without a refill.
 *
 * <p>No boss is named here: the row decides. Guarded for a CommonLib jar without
 * {@code EncounterRuntime.companionScale}, the way {@link EncounterBinding} guards the bound-subject read.
 */
public final class EncounterSubjectScaling {

    private static final AtomicBoolean FRAMEWORK_MISSING = new AtomicBoolean();

    /** The scale each live fight's boss took at its engage, by run, until its reset: the level is read once. */
    private static final Map<UUID, MobScaleResult> BY_RUN = new ConcurrentHashMap<>();

    /** More fights than a server holds at once; past it the remembered scales are let go (a reset drops each). */
    private static final int MAX_RUNS = 256;

    private EncounterSubjectScaling() {
    }

    /**
     * The fight is on: when its row lets this mod scale its boss, scale him on the world's next task. Never
     * throws.
     */
    public static void onEngaged(@Nonnull EncounterEngagedEvent event) {
        try {
            if (FRAMEWORK_MISSING.get() || !MobScalingConfig.getInstance().isEnabled() || event.worldUuid() == null) {
                return;
            }
            World world = Universe.get().getWorld(event.worldUuid());
            if (world == null || !world.isAlive()) {
                return;
            }
            if (companionScale(world.getEntityStore().getStore(), event.runId()) == null) {
                return; // the row lets nothing be scaled: no task at all
            }
            UUID runId = event.runId();
            world.execute(() -> scaleAtEngage(world, runId));
        } catch (Throwable t) {
            safeWarn("encounter boss scale at the engage failed: " + t);
        }
    }

    /** The run is over: forget the scale its boss took. Never throws. */
    public static void onReset(@Nonnull EncounterResetEvent event) {
        try {
            BY_RUN.remove(event.runId());
        } catch (Throwable ignored) {
            // nothing to release
        }
    }

    /**
     * The roll's skip for a reloaded boss: when {@code ref} is the boss of an engaged fight whose row lets
     * this mod scale him, put back the scale that fight's engage gave him (read again only when this session
     * never saw the engage) without a refill, and answer true; false leaves the caller's strip to run. Never
     * throws.
     */
    static boolean rescaleReloaded(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull World world) {
        try {
            if (FRAMEWORK_MISSING.get()) {
                return false;
            }
            EncounterRuntime.CompanionScale companion = companionScaleOf(store, ref);
            if (companion == null || !companion.engaged()) {
                return false;
            }
            MobScaleResult result = scaleFor(world, store, ref, companion);
            if (result == null) {
                return false;
            }
            apply(store, ref, result, accessor, false);
            safeInfo("encounter boss rescaled after a reload: " + describe(store, ref, world, companion, result));
            return true;
        } catch (Throwable t) {
            safeWarn("encounter boss rescale after a reload failed: " + t);
            return false;
        }
    }

    /** The engage's task, on the world thread outside any system. */
    private static void scaleAtEngage(@Nonnull World world, @Nonnull UUID runId) {
        try {
            Store<EntityStore> store = world.getEntityStore().getStore();
            EncounterRuntime.CompanionScale companion = companionScale(store, runId);
            if (companion == null || !companion.engaged()) {
                return;
            }
            Ref<EntityStore> subject = companion.subject();
            BY_RUN.remove(runId); // the engage is where a fight's level is read
            MobScaleResult result = scaleFor(world, store, subject, companion);
            if (result == null) {
                return;
            }
            apply(store, subject, result, store, true);
            safeInfo("encounter boss scaled at the engage: " + describe(store, subject, world, companion, result));
        } catch (Throwable t) {
            safeWarn("encounter boss scale at the engage failed: " + t);
        }
    }

    /**
     * The scale {@code subject} takes for this fight, or null when this mod scales nothing there: the mod or
     * the world's scaling switched off, or the boss excluded or not hostile ({@link MobClassifier}, the same
     * layers his re-add on every role change meets). The fight's remembered scale when it has one, else the
     * level his adds read where he stands, folded and remembered.
     */
    @Nullable
    private static MobScaleResult scaleFor(@Nonnull World world, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> subject, @Nonnull EncounterRuntime.CompanionScale companion) {
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        if (!cfg.isEnabled() || !subject.isValid()) {
            return null;
        }
        SpawnScalingSettings spawn = cfg.spawnSettingsFor(world);
        if (!spawn.isWorldScalingEnabled()) {
            return null;
        }
        NPCEntity npc = store.getComponent(subject, NPCEntity.getComponentType());
        if (npc == null) {
            return null;
        }
        Byte scope = MobClassifier.classify(npc, store.getComponent(subject, WorldSupport.getComponentType()));
        if (scope == null) {
            return null;
        }
        MobScaleResult remembered = BY_RUN.get(companion.runId());
        if (remembered != null) {
            return remembered;
        }
        MobScalingSpawnHook.SpawnScaling level = MobScalingRollSystem.resolveSpawnScaling(world, store, subject, spawn);
        MobScaleResult result = MobScaleFold.anchored(level.difficulty(), level.effectiveFloor(), scope,
                spawn.statCurveModel(), companion.health(), companion.damage());
        if (BY_RUN.size() >= MAX_RUNS) {
            BY_RUN.clear();
        }
        BY_RUN.put(companion.runId(), result);
        return result;
    }

    /**
     * Stamp {@code result} and put its health and damage on {@code ref}. A first write (the engage) strips any
     * earlier {@code mmoscaling_hp} first, so the write is a first apply and fills the bar to its new top; a
     * reload's write only reconciles. {@code accessor} is the roll's command buffer, or the store on a task.
     */
    private static void apply(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull MobScaleResult result, @Nonnull ComponentAccessor<EntityStore> accessor, boolean firstWrite) {
        accessor.putComponent(ref, ScaledMobComponent.getComponentType(), ScaledMobComponent.ofCompanion(result));
        if (firstWrite) {
            HealthUtil.reconcileMaxHealth(store, ref, 1.0, MobScalingSpawnHook.HP_KEY);
        }
        HealthUtil.reconcileMaxHealth(store, ref, result.hpMult(), MobScalingSpawnHook.HP_KEY);
        MobScalingEffectApplySystem.reconcile(ref, result, accessor);
    }

    /** The runtime's per-run read, latched off for good on a CommonLib jar that lacks it. */
    @Nullable
    private static EncounterRuntime.CompanionScale companionScale(@Nonnull Store<EntityStore> store,
            @Nonnull UUID runId) {
        try {
            return EncounterRuntime.companionScale(store, runId);
        } catch (LinkageError e) {
            frameworkMissing(e);
            return null;
        }
    }

    /** The runtime's per-subject read, latched off for good on a CommonLib jar that lacks it. */
    @Nullable
    private static EncounterRuntime.CompanionScale companionScaleOf(@Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref) {
        try {
            return EncounterRuntime.companionScaleOf(store, ref);
        } catch (LinkageError e) {
            frameworkMissing(e);
            return null;
        }
    }

    private static void frameworkMissing(@Nonnull LinkageError e) {
        if (FRAMEWORK_MISSING.compareAndSet(false, true)) {
            safeWarn("EncounterRuntime.companionScale is unavailable on this ziggfreed-common jar (needs 2.5.0 or "
                    + "newer), so an encounter's boss is never scaled, whatever his fight's binding row allows. Cause: "
                    + e);
        }
    }

    @Nonnull
    private static String describe(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull World world, @Nonnull EncounterRuntime.CompanionScale companion, @Nonnull MobScaleResult result) {
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        String role = npc == null || npc.getRoleName() == null ? "?" : npc.getRoleName();
        return String.format(Locale.ROOT, "%s (%s) in %s at level %.1f: health x%.3f, hits x%.3f", role,
                companion.encounterId(), world.getName(), result.difficulty(), result.hpMult(), result.outDmgMult());
    }

    private static void safeInfo(@Nonnull String message) {
        try {
            MobScalingPlugin.LOGGER.atInfo().log(message);
        } catch (Throwable ignored) {
            // log-manager-less JVMs
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
