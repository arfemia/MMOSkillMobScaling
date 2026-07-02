package com.ziggfreed.mmomobscaling.event;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.HolderSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsSystems;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.systems.RoleBuilderSystem;
import com.ziggfreed.common.health.HealthUtil;
import com.ziggfreed.mmoskilltree.world.WorldRules;
import com.ziggfreed.mmoskilltree.world.WorldScope;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.component.ScaledMobComponent;
import com.ziggfreed.mmomobscaling.config.MobScalingConfig;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.roster.Rosters;
import com.ziggfreed.mmomobscaling.scaling.MobScaleFold;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;
import com.ziggfreed.common.util.SplitMix64;

/**
 * The spawn-lock: a {@link HolderSystem} over the structural {@code Archetype.of(NPCEntity, EntityStatMap)}
 * query (copied from {@code BalancingInitialisationSystem}), ordered {@code AFTER RoleBuilderSystem +
 * EntityStatsSystems.Setup} so the role + stat map are built. It resolves a mob's difficulty ONCE at add,
 * rolls rarity + affixes deterministically, folds the frozen {@link ScaledMobComponent} onto the pre-add
 * holder, and scales HP via the ref-less {@code HealthUtil.scaleMaxHealth} (maximized). Zero per-tick cost.
 *
 * <p>Native-asset-first: the aura + affix EFFECTS are applied post-add by {@code MobScalingEffectApplySystem}
 * (a companion {@code RefSystem}); this pre-add step only stamps data + scales HP (ref-less, maximized).
 *
 * <p><b>MVP scope:</b> {@code effDifficulty} is the world-baseline floor ({@code WorldRules.mobDifficultyFloor});
 * the zone/biome/trigger-volume resolver + the open-world region-power group delta are follow-ups (the
 * {@code ScalingEngine} returns the floor when participants are empty, so those drop in cleanly later).
 * The whole body is one defensive try/catch so a throw never breaks chunk loading.
 */
public final class MobScalingSpawnHook extends HolderSystem<EntityStore> {

    /** Mod-prefixed HP-modifier key (the {@code scaleMaxHealth} idempotency handle). */
    private static final String HP_KEY = "mmoscaling_hp";

    @Nonnull private final ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
    @Nonnull private final ComponentType<EntityStore, EntityStatMap> statType = EntityStatMap.getComponentType();

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, RoleBuilderSystem.class),
            new SystemDependency<>(Order.AFTER, EntityStatsSystems.Setup.class));

    @Nonnull
    private final Query<EntityStore> query = Archetype.of(npcType, statType);

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void onEntityAdd(@Nonnull Holder<EntityStore> holder, @Nonnull AddReason reason,
            @Nonnull Store<EntityStore> store) {
        try {
            MobScalingConfig cfg = MobScalingConfig.getInstance();
            if (!cfg.isEnabled()) {
                cleanupResidue(holder); // runtime soft-disable: strip any stale scaling off saved mobs
                return;
            }
            World world = store.getExternalData().getWorld();
            if (world == null) {
                return;
            }
            // ONE snapshot of the per-world rules (volatile cache): used for the kill-switch AND the floor.
            WorldRules rules = WorldScope.rulesFor(world);
            if (!rules.mobScalingEnabled()) {
                cleanupResidue(holder); // per-world kill-switch flipped off: strip stale scaling
                return;
            }

            NPCEntity npc = holder.getComponent(npcType);
            if (npc == null) {
                return; // guaranteed by the structural query, but guard anyway
            }
            Byte scope = MobClassifier.classify(npc);
            if (scope == null) {
                cleanupResidue(holder); // now EXCLUDED (e.g. a role added to the exclude set): strip stale scaling
                return;
            }

            double effDifficulty = rules.mobDifficultyFloor(); // MVP: no group/region delta yet (follow-up)

            SplitMix64 rng = new SplitMix64(seedFor(npc, world, holder));

            Rarity rarity = Rosters.rarity().pick(effDifficulty, cfg.getRaritySpawnChance(), rng);
            List<Affix> affixes = rarity == null
                    ? List.of()
                    : Rosters.affix().pick(effDifficulty, rarity, rarity.affixSlots(), rng);

            MobScaleResult result = MobScaleFold.fold(rarity, affixes, effDifficulty, scope);
            holder.addComponent(ScaledMobComponent.getComponentType(), new ScaledMobComponent(result));

            // HP: NATIVE EntityStats - a multiplicative MAX StaticModifier on the EntityStatMap, ref-less on
            // the pre-add holder. RECONCILE (not add-only): converge the mmoscaling_hp modifier to the fresh
            // hpMult, so a retune / floor / rarity change on chunk reload never strands a stale inflated max
            // (hpMult==1 removes any prior modifier; a shrink auto-clamps current HP; the first apply heals).
            // The companion effect system sweeps stale Mmoscaling_* auras the same add cycle.
            HealthUtil.reconcileMaxHealth(holder, result.hpMult(), HP_KEY);
        } catch (Throwable t) {
            safeWarn("spawn scale failed: " + t);
        }
    }

    @Override
    public void onEntityRemoved(@Nonnull Holder<EntityStore> holder, @Nonnull RemoveReason reason,
            @Nonnull Store<EntityStore> store) {
        // No-op: the transient component + native effect clear on death own teardown; nothing to release.
    }

    /**
     * Strip stale native scaling off a mob that should NOT be scaled on this load (mod runtime-disabled, world
     * kill-switch off, or newly excluded). Removes the {@code mmoscaling_hp} MAX modifier; if there WAS residue
     * (the mob was scaled before), stamps a PLAIN {@link ScaledMobComponent} so the effect {@code RefSystem}
     * fires and sweeps the stale {@code Mmoscaling_*} auras the same add cycle. Cheap no-op for a mob that was
     * never scaled (the modifier is absent, so nothing is stamped and no per-entity cost is added).
     */
    private static void cleanupResidue(@Nonnull Holder<EntityStore> holder) {
        boolean hadResidue = HealthUtil.reconcileMaxHealth(holder, 1.0, HP_KEY);
        if (hadResidue) {
            holder.addComponent(ScaledMobComponent.getComponentType(),
                    new ScaledMobComponent(MobScaleFold.plain(0.0, MobScaleResult.SCOPE_HOSTILE)));
        }
    }

    /**
     * A restart-STABLE, per-ENTITY deterministic seed: the entity's UUID folded with the world seed. The UUID
     * is persisted with the entity and restored UNCHANGED on chunk reload / restart (unlike a drifting
     * position, which re-rolls a moved mob differently, or the restart-unstable {@code roleIndex}), so the
     * SAME mob re-rolls the SAME rarity/affixes/mults every time - the fix for the reload re-roll drift.
     * Falls back to a stable per-role seed ({@code roleName} is restart-stable, NOT position) only if the
     * UUID is somehow absent pre-add.
     */
    private static long seedFor(@Nonnull NPCEntity npc, @Nonnull World world, @Nonnull Holder<EntityStore> holder) {
        long worldSeed = world.getWorldConfig().getSeed();
        UUIDComponent uuidComp = holder.getComponent(UUIDComponent.getComponentType());
        if (uuidComp != null) {
            UUID uuid = uuidComp.getUuid();
            if (uuid != null) {
                long entitySeed = SplitMix64.mix(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
                return SplitMix64.mix(entitySeed, worldSeed);
            }
        }
        String roleName = npc.getRoleName();
        long roleHash = roleName != null ? roleName.hashCode() : npc.getRoleIndex();
        return SplitMix64.mix(roleHash, worldSeed);
    }

    private static void safeWarn(@Nonnull String message) {
        try {
            MobScalingPlugin.LOGGER.atWarning().log(message);
        } catch (Throwable ignored) {
            // log-manager-less JVMs
        }
    }
}
