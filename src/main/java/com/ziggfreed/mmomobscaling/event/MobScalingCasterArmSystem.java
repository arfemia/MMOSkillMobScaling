package com.ziggfreed.mmomobscaling.event;

import java.util.ArrayList;
import java.util.List;

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
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.caster.CasterCadence;
import com.ziggfreed.mmomobscaling.caster.CasterEntry;
import com.ziggfreed.mmomobscaling.caster.CasterRoster;
import com.ziggfreed.mmomobscaling.caster.CasterRosterMatcher;
import com.ziggfreed.mmomobscaling.caster.NativeChainAttacker;
import com.ziggfreed.mmomobscaling.component.CasterKitComponent;
import com.ziggfreed.mmomobscaling.component.ScaledMobComponent;
import com.ziggfreed.mmomobscaling.roster.Rosters;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;

/**
 * The post-add companion to {@code MobScalingSpawnHook} that arms a scaled mob's caster kit: a
 * {@link RefSystem} (query = the {@code ScaledMobComponent} archetype, mirroring
 * {@code MobScalingEffectApplySystem} exactly) that fires with a VALID ref the same add cycle,
 * resolves the mob's role via {@code CasterRosterMatcher}, gates each candidate ability against the
 * frozen {@link MobScaleResult}, and stamps a {@link CasterKitComponent} holding only the entries that
 * cleared the gate.
 *
 * <p>A {@code NATIVE_CHAIN} entry is armed onto the mob's native {@code CombatSupport} ONCE HERE (via
 * {@link NativeChainAttacker#arm}) and is deliberately NOT tracked in the stamped kit - native
 * {@code CombatSupport.addAttackOverride} appends to a round-robin the engine cycles forever and never
 * expires on its own, so one arm-at-spawn call suffices. Re-arming it on the tick system's cadence
 * would do worse than nothing: {@code addAttackOverride} resets the round-robin's cursor
 * ({@code attackOverrideIndex}) to 0 on every call, so a re-arm from ANY entry's cadence would
 * constantly snap the cursor back to the first-armed chain, starving every other {@code NATIVE_CHAIN}
 * entry in a multi-chain roster. An {@code ABILITY} entry does nothing here beyond scheduling - the
 * actual {@code MMOSkillTreeAPI.castNpcAbility} call happens on the cadence system's tick, never at arm
 * time.
 *
 * <p>The common case (no roster targets this mob's role, or the roster's entries all fail the
 * difficulty/rarity/scope gate) adds NO component at all - zero per-tick cost downstream, since the
 * cadence system's query excludes any mob without {@link CasterKitComponent}. Whole body try-guarded;
 * idempotent (a chunk reload re-adds {@code ScaledMobComponent}, which re-fires this system, which
 * re-resolves and re-stamps identically).
 */
public final class MobScalingCasterArmSystem extends RefSystem<EntityStore> {

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
            List<CasterRoster> rosters = Rosters.casterRosters();
            if (rosters.isEmpty()) {
                return; // no CasterRoster content loaded at all: cheapest possible no-op
            }
            NPCEntity npc = cb.getComponent(ref, NPCEntity.getComponentType());
            if (npc == null) {
                return;
            }
            String roleName = npc.getRoleName();
            if (roleName == null) {
                return;
            }
            CasterRoster roster = CasterRosterMatcher.match(roleName, rosters);
            if (roster == null || roster.abilities().isEmpty()) {
                return;
            }

            MobScaleResult result = comp.result();
            Role role = npc.getRole();
            long now = System.currentTimeMillis();
            List<CasterKitComponent.Armed> armed = new ArrayList<>();
            for (CasterEntry entry : roster.abilities()) {
                if (!entry.isEligible(result.difficulty(), result.rarityId(), result.scope())) {
                    continue;
                }
                if (entry.kind() == CasterEntry.Kind.NATIVE_CHAIN) {
                    if (role != null) {
                        NativeChainAttacker.arm(role, entry.nativeChain());
                    }
                    // Armed once, right here, at spawn - NEVER added to the kit's armed/tick list.
                    // MobScalingCasterTickSystem never schedules or re-arms a NATIVE_CHAIN entry (see
                    // its NATIVE_CHAIN case for why re-arming would starve other chains).
                    continue;
                }
                long dueAt = CasterCadence.nextDueAt(now, entry.cadenceMs(), entry.jitterMs());
                armed.add(new CasterKitComponent.Armed(entry, dueAt));
            }
            if (armed.isEmpty()) {
                return;
            }
            cb.addComponent(ref, CasterKitComponent.getComponentType(), new CasterKitComponent(armed));
        } catch (Throwable t) {
            safeWarn("caster arm failed: " + t);
        }
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> cb) {
        // No-op: CasterKitComponent is transient (not persisted) and CombatSupport's attack-override
        // list lives on the (also transient, per-entity) Role/NPCEntity - nothing to release here.
    }

    private static void safeWarn(@Nonnull String message) {
        try {
            MobScalingPlugin.LOGGER.atWarning().log(message);
        } catch (Throwable ignored) {
            // log-manager-less JVMs
        }
    }
}
