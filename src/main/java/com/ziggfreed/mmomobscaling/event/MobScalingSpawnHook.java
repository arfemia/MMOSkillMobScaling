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
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentDisplayName;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsSystems;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.systems.RoleBuilderSystem;
import com.ziggfreed.common.health.HealthUtil;
import com.ziggfreed.common.scaling.ScalingContext;
import com.ziggfreed.common.scaling.ScalingEngine;
import com.ziggfreed.mmoskilltree.world.WorldRules;
import com.ziggfreed.mmoskilltree.world.WorldScope;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.component.ScaledMobComponent;
import com.ziggfreed.mmomobscaling.config.MobScalingConfig;
import com.ziggfreed.mmomobscaling.config.RarityConfig;
import com.ziggfreed.mmomobscaling.i18n.MobScalingTextUtil;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.roster.Rosters;
import com.ziggfreed.mmomobscaling.scaling.MobScaleFold;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;
import com.ziggfreed.mmomobscaling.scaling.RegionPowerTracker;
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
 * <p><b>Difficulty scope:</b> {@code effDifficulty} = the per-world floor ({@code WorldRules.mobDifficultyFloor})
 * plus the band-clamped open-world GROUP DELTA off the cached per-region player-power scalar
 * ({@link RegionPowerTracker} + ziggfreed-common's {@code ScalingEngine}; see {@code resolveDifficulty}).
 * The zone/biome/trigger-volume floor resolver remains a follow-up (it will replace the flat floor input).
 * The whole body is one defensive try/catch so a throw never breaks chunk loading.
 */
public final class MobScalingSpawnHook extends HolderSystem<EntityStore> {

    /** Mod-prefixed HP-modifier key (the {@code scaleMaxHealth} idempotency handle; the purge command strips it too). */
    public static final String HP_KEY = "mmoscaling_hp";

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

            double effDifficulty = resolveDifficulty(rules, world, holder, cfg);

            SplitMix64 rng = new SplitMix64(seedFor(npc, world, holder));

            Rarity rarity;
            if (scope == MobScaleResult.SCOPE_BOSS) {
                // Boss scope FORCES the authored boss tier (Weight 0 keeps it off the normal roll);
                // an owner who stripped Rarities/Boss.json falls back to the normal curve.
                rarity = RarityConfig.getInstance().resolve("boss");
                if (rarity == null) {
                    rarity = Rosters.rarity().pick(effDifficulty, cfg.getRaritySpawnChance(), rng);
                }
            } else {
                rarity = Rosters.rarity().pick(effDifficulty, cfg.getRaritySpawnChance(), rng);
            }
            List<Affix> affixes = rarity == null
                    ? List.of()
                    : Rosters.affix().pick(effDifficulty, rarity, rarity.affixSlots(), rng);

            MobScaleResult result = MobScaleFold.fold(rarity, affixes, effDifficulty, scope);
            holder.addComponent(ScaledMobComponent.getComponentType(), new ScaledMobComponent(result));

            if (rarity != null) {
                decorateDisplayName(holder, rarity);
            }

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
     * Stamp the rarity-decorated display name (surfaces in DEATH MESSAGES / kill feed / logs - the engine
     * does not render {@code DisplayNameComponent} as an overhead nameplate). Composes the localized FRAME
     * key {@code scaling.name.decorated} ({@code {rarity} {base}}) with NESTED client-resolved {@code Message}
     * params - never a joined/raw English-order string - so every locale reorders the frame its own way.
     * Reads the base name RoleBuilderSystem already stamped this same add cycle (we order AFTER it), so a
     * reload never double-decorates (the builder re-stamps the plain role name first). SKIPS a mob carrying
     * {@code PersistentDisplayName} (a player-authored custom name must never be overwritten) - the same
     * guard RoleBuilderSystem itself uses.
     */
    private static void decorateDisplayName(@Nonnull Holder<EntityStore> holder, @Nonnull Rarity rarity) {
        if (holder.getComponent(PersistentDisplayName.getComponentType()) != null) {
            return;
        }
        DisplayNameComponent existing = holder.getComponent(DisplayNameComponent.getComponentType());
        Message base = existing != null ? existing.getDisplayName() : null;
        if (base == null) {
            return; // no base name to decorate (nameless archetype)
        }
        Message decorated = Message.translation("scaling.name.decorated")
                .param("rarity", Message.translation(MobScalingTextUtil.rarityNameKey(rarity)))
                .param("base", base);
        holder.putComponent(DisplayNameComponent.getComponentType(), new DisplayNameComponent(decorated));
    }

    /**
     * The effective difficulty for this spawn: the per-world floor plus the band-clamped open-world
     * GROUP DELTA - the cached per-region player-power scalar ({@link RegionPowerTracker}, O(1),
     * maintained on player region-cross, NEVER a per-spawn scan) resolved through ziggfreed-common's
     * {@code ScalingEngine} under the configured aggregation mode + band/caps. A cold region (no
     * players tracked, scalar {@code <= 0}) or a missing pre-add {@code TransformComponent} is a
     * ZERO delta: the authored floor stands untouched. This is what makes {@code MinDifficulty}
     * above the floor a live lever (a strong group pushes {@code effDifficulty} past the floor, so
     * Legendary / Freezing bands become reachable).
     */
    private static double resolveDifficulty(@Nonnull WorldRules rules, @Nonnull World world,
            @Nonnull Holder<EntityStore> holder, @Nonnull MobScalingConfig cfg) {
        TransformComponent transform = holder.getComponent(TransformComponent.getComponentType());
        if (transform == null) {
            return rules.mobDifficultyFloor();
        }
        return effectiveDifficulty(rules, world,
                ChunkUtil.chunkCoordinate(transform.getPosition().x),
                ChunkUtil.chunkCoordinate(transform.getPosition().z), cfg);
    }

    /**
     * The chunk-coordinate form of the difficulty resolve, shared with the {@code /mobscaling inspect}
     * diagnostic so the command reports EXACTLY what a spawn at that spot would resolve.
     */
    public static double effectiveDifficulty(@Nonnull WorldRules rules, @Nonnull World world,
            int chunkX, int chunkZ, @Nonnull MobScalingConfig cfg) {
        double floor = rules.mobDifficultyFloor();
        long regionKey = RegionPowerTracker.regionKey(chunkX, chunkZ, cfg.getRegionSizeChunks());
        double regionPower = RegionPowerTracker.get().scalarFor(world.getName(), regionKey);
        if (regionPower <= 0.0) {
            return floor; // cold miss: no players tracked in this region, the floor stands
        }
        return ScalingEngine.resolve(
                ScalingContext.openWorld(floor, regionPower, MobScalingPresenceSystem.mode(cfg)),
                cfg.getGroupDeltaBandWidth(), cfg.getDifficultyMinCap(), cfg.getDifficultyMaxCap());
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
