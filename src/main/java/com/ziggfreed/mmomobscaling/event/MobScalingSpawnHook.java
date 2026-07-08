package com.ziggfreed.mmomobscaling.event;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsSystems;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.systems.RoleBuilderSystem;
import com.ziggfreed.common.health.HealthUtil;
import com.ziggfreed.common.scaling.ScalingContext;
import com.ziggfreed.common.scaling.ScalingEngine;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.component.ScaledMobComponent;
import com.ziggfreed.mmomobscaling.config.MobScalingConfig;
import com.ziggfreed.mmomobscaling.config.RarityConfig;
import com.ziggfreed.mmomobscaling.config.SpawnScalingSettings;
import com.ziggfreed.mmomobscaling.family.MobFamilyMatcher;
import com.ziggfreed.mmomobscaling.i18n.MobScalingTextUtil;
import com.ziggfreed.mmomobscaling.pages.RoleBaseHealthResolver;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.roster.Rosters;
import com.ziggfreed.mmomobscaling.scaling.MobScaleFold;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;
import com.ziggfreed.mmomobscaling.scaling.RegionPowerTracker;
import com.ziggfreed.mmomobscaling.variant.Variant;
import com.ziggfreed.mmomobscaling.world.ZoneDifficultyResolver;
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
 * <p><b>Difficulty scope:</b> {@code effDifficulty} = the LAYERED floor (native zone &gt; biome &gt;
 * the per-world/global {@code Difficulty.Floor} baseline, via {@link ZoneDifficultyResolver}) plus the
 * distance-from-spawn escalation, plus the band-clamped open-world GROUP DELTA off the cached
 * per-(zone, sub-grid) player-power scalar ({@link RegionPowerTracker} + ziggfreed-common's
 * {@code ScalingEngine}; see {@link #resolveSpawnScaling}). The escalation also boosts the rarity
 * spawn chance, so the deep frontier is denser with scaled mobs, not just higher-band.
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
            // ONE per-world settings resolve (cached view; the GLOBAL config when no Worlds/*.json rule
            // matches): the kill-switch, the floor, the caps, the pool gates - everything reads off it.
            SpawnScalingSettings spawn = cfg.spawnSettingsFor(world.getName());
            if (!spawn.isWorldScalingEnabled()) {
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

            SpawnScaling scaling = resolveSpawnScaling(world, holder, spawn);
            double effDifficulty = scaling.difficulty();

            SplitMix64 rng = new SplitMix64(seedFor(npc, world, holder));

            // The per-mob family gate: narrows which rarity tiers / variant overlays may apply to THIS mob (an
            // authored Families allow/deny block resolved against the mob's role name + native NPCGroup
            // membership), ANDed with the per-world Pool allow/deny gate (1.0.2). Both are pure functions of
            // stable identity/config, so they consume no RNG and keep the roll deterministic. Unrestricted
            // tiers short-circuit cheaply (the common case).
            Predicate<Rarity> rarityFamilyEligible = r -> spawn.isRarityAllowed(r.id())
                    && MobFamilyMatcher.get().eligible(r.familyFilter(), npc);
            Predicate<Variant> variantFamilyEligible = v -> spawn.isVariantAllowed(v.id())
                    && MobFamilyMatcher.get().eligible(v.familyFilter(), npc);

            Rarity rarity;
            if (scope == MobScaleResult.SCOPE_BOSS) {
                // Boss scope FORCES the authored boss tier (Weight 0 keeps it off the normal roll);
                // an owner who stripped Rarities/Boss.json falls back to the normal (family-gated) curve.
                rarity = RarityConfig.getInstance().resolve("boss");
                if (rarity == null) {
                    rarity = Rosters.rarity().pick(effDifficulty, scaling.raritySpawnChance(), rng, rarityFamilyEligible);
                }
            } else {
                rarity = Rosters.rarity().pick(effDifficulty, scaling.raritySpawnChance(), rng, rarityFamilyEligible);
            }
            // The SECOND axis: an independent family-gated variant OVERLAY (at most one), rolled after the base
            // rarity. Draws exactly once (VariantRoster) so the seed->result mapping stays stable. The base
            // rarity id (or "" for a plain mob) feeds the variant's requires-rarity gate.
            String baseRarityId = rarity != null ? rarity.id() : "";
            Variant variant = Rosters.variant().pick(effDifficulty, baseRarityId,
                    spawn.getVariantChanceMultiplier(), rng, variantFamilyEligible);

            // Affixes come from BOTH hosts (rarity slots + variant slots) plus the per-world extra slots,
            // one combined distinct roll that shares the used-set + single-resistance cap; the per-world
            // Pool.Affixes allow/deny gates every draw (1.0.2).
            List<Affix> affixes = Rosters.affix().pick(effDifficulty, rarity, variant,
                    spawn.getExtraAffixSlots(), a -> spawn.isAffixAllowed(a.id()), rng);

            // The base difficulty->stat curve scales plain + rare mobs alike; rarity/affix mults stack on top,
            // then the variant multiplier stacks multiplicatively over that.
            MobScaleFold.DifficultyStatCurve curve = spawn.statCurveModel();
            MobScaleResult result = MobScaleFold.fold(rarity, variant, affixes, effDifficulty, scope, curve);
            holder.addComponent(ScaledMobComponent.getComponentType(), new ScaledMobComponent(result));

            if (rarity != null || variant != null) {
                decorateDisplayName(holder, rarity, variant);
            }

            // Observed-spawn ground truth for the admin-page preview (round-3 hardening): the CURRENT
            // Health-stat max, read BEFORE we touch it below - the balanced base PLUS whatever any
            // earlier-ordered mod's modifier already stacked on, never our OWN mmoscaling_hp modifier
            // (this system is what applies that one, and only after this read). Best-effort; never
            // allowed to disturb the actual HP scaling that follows.
            recordObservedBaseHealth(npc, holder);

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
                    new ScaledMobComponent(MobScaleFold.plain(0.0, MobScaleResult.SCOPE_HOSTILE,
                            MobScaleFold.DifficultyStatCurve.NONE)));
        }
    }

    /**
     * Feed {@link RoleBaseHealthResolver} a ground-truth base-health reading for this role (round-3
     * hardening): the {@code Health} stat's CURRENT max, read via the cached {@link #statType} component
     * type right before {@link HealthUtil#reconcileMaxHealth} applies the {@code mmoscaling_hp}
     * modifier - so it is the native-balanced base PLUS whatever any earlier-ordered mod's own modifier
     * already stacked on, never this system's own key (applied only after this read). The admin page's
     * skeleton preview prefers this live reading over {@link RoleBaseHealthResolver}'s reflective
     * template read. Allocation-free, O(1); fully try-guarded so a failure here can never skip the actual
     * HP scaling below (a display-only diagnostic must never gate gameplay).
     */
    private void recordObservedBaseHealth(@Nonnull NPCEntity npc, @Nonnull Holder<EntityStore> holder) {
        try {
            String roleName = npc.getRoleName();
            if (roleName == null) {
                return;
            }
            EntityStatMap stats = holder.getComponent(statType);
            if (stats == null) {
                return;
            }
            EntityStatValue health = stats.get(DefaultEntityStatTypes.getHealth());
            if (health == null) {
                return;
            }
            // A RELOADED already-scaled mob still carries the persisted mmoscaling_hp modifier at this
            // point (reconcile below is what converges it) - its max is post-scale, NOT base. Skip it;
            // fresh spawns supply the true base reading.
            if (health.getModifier(HP_KEY) != null) {
                return;
            }
            RoleBaseHealthResolver.recordObserved(roleName, Math.round(health.getMax()));
        } catch (Throwable ignored) {
            // best-effort: the preview simply falls back to RoleBaseHealthResolver's template read
        }
    }

    /**
     * Stamp the rarity/variant-decorated display name (surfaces in DEATH MESSAGES / kill feed / logs - the
     * engine does not render {@code DisplayNameComponent} as an overhead nameplate). Composes localized FRAME
     * keys with NESTED client-resolved {@code Message} params - never a joined/raw English-order string - so
     * every locale reorders the frame its own way: the rarity frame {@code scaling.name.decorated}
     * ({@code {rarity} {base}}) wraps the base, then the variant frame {@code scaling.name.variant_decorated}
     * ({@code {variant} {inner}}) wraps THAT (so "Horrific Epic Spider"). Either host may be absent (a
     * variant-only mob is "Horrific Spider"; caller guarantees at least one is present). Reads the base name
     * RoleBuilderSystem already stamped this same add cycle (we order AFTER it), so a reload never
     * double-decorates. SKIPS a mob carrying {@code PersistentDisplayName} (a player-authored custom name must
     * never be overwritten) - the same guard RoleBuilderSystem itself uses.
     */
    private static void decorateDisplayName(@Nonnull Holder<EntityStore> holder, @Nullable Rarity rarity,
            @Nullable Variant variant) {
        if (holder.getComponent(PersistentDisplayName.getComponentType()) != null) {
            return;
        }
        DisplayNameComponent existing = holder.getComponent(DisplayNameComponent.getComponentType());
        Message base = existing != null ? existing.getDisplayName() : null;
        if (base == null) {
            return; // no base name to decorate (nameless archetype)
        }
        Message decorated = base;
        if (rarity != null) {
            decorated = Message.translation("scaling.name.decorated")
                    .param("rarity", Message.translation(MobScalingTextUtil.rarityNameKey(rarity)))
                    .param("base", decorated);
        }
        if (variant != null) {
            decorated = Message.translation("scaling.name.variant_decorated")
                    .param("variant", Message.translation(MobScalingTextUtil.variantNameKey(variant)))
                    .param("inner", decorated);
        }
        holder.putComponent(DisplayNameComponent.getComponentType(), new DisplayNameComponent(decorated));
    }

    /**
     * One fully-resolved spawn-scaling read: the effective difficulty (floor + escalation + group
     * delta), the escalation-boosted rarity spawn chance, and the diagnostic breakdown
     * ({@code /mobscaling inspect} prints every field so owners can see exactly which layer produced
     * a number).
     */
    public record SpawnScaling(double difficulty, double raritySpawnChance, @Nonnull String zoneName,
            double baseFloor, double escalationBonus, double effectiveFloor, double regionPower,
            @Nonnull String biomeName) {
    }

    /**
     * The holder form of the spawn-scaling resolve. A missing pre-add {@code TransformComponent}
     * degrades to the world baseline floor + the un-boosted chance (no position = no zone, no
     * escalation, no group delta).
     */
    private static SpawnScaling resolveSpawnScaling(@Nonnull World world,
            @Nonnull Holder<EntityStore> holder, @Nonnull SpawnScalingSettings settings) {
        TransformComponent transform = holder.getComponent(TransformComponent.getComponentType());
        if (transform == null) {
            double floor = settings.getDifficultyFloor();
            return new SpawnScaling(floor, settings.getRaritySpawnChance(), ZoneDifficultyResolver.NO_ZONE,
                    floor, 0.0, floor, 0.0, ZoneDifficultyResolver.NO_ZONE);
        }
        return resolveSpawnScaling(world,
                ChunkUtil.chunkCoordinate(transform.getPosition().x),
                ChunkUtil.chunkCoordinate(transform.getPosition().z), settings);
    }

    /**
     * The chunk-coordinate form of the spawn-scaling resolve, shared with the HUD and the
     * {@code /mobscaling inspect} diagnostic so both report EXACTLY what a spawn at that spot would
     * resolve. Pipeline: {@link ZoneDifficultyResolver} produces the layered floor (native zone &gt;
     * biome &gt; world baseline) plus the distance escalation (which also boosts the rarity chance);
     * then the cached per-(zone, sub-grid) player-power scalar ({@link RegionPowerTracker}, O(1),
     * maintained on player region-cross, NEVER a per-spawn scan) rides on top through
     * ziggfreed-common's {@code ScalingEngine} under the configured aggregation mode + band/caps. A
     * cold region (no players tracked, scalar {@code <= 0}) is a ZERO delta: the escalated floor
     * stands. Power scaling is also fully OFF inside the start ring near world spawn
     * ({@code floor.insideStartRing()}), and when {@code OnlyRaiseDifficulty} is set the group delta only
     * ever RAISES difficulty above the floor, never below it. This is what makes {@code MinDifficulty}
     * above the floor a live lever (a strong group pushes {@code effDifficulty} past the floor, so
     * Legendary / Freezing bands become reachable).
     */
    public static SpawnScaling resolveSpawnScaling(@Nonnull World world,
            int chunkX, int chunkZ, @Nonnull SpawnScalingSettings settings) {
        ZoneDifficultyResolver.ResolvedFloor floor =
                ZoneDifficultyResolver.get().resolve(world, chunkX, chunkZ, settings);
        RegionPowerTracker.RegionKey regionKey = new RegionPowerTracker.RegionKey(floor.zoneName(),
                RegionPowerTracker.gridKey(chunkX, chunkZ, settings.getRegionSizeChunks()));
        double regionPower = RegionPowerTracker.get().scalarFor(world.getName(), regionKey);
        // LOCATION drives difficulty (the escalated floor). Player/group power only ever RAISES it above
        // that floor (never lowers it, when OnlyRaiseDifficulty is set), and is fully OFF inside the start
        // ring near world spawn, so a fresh newcomer's home zone is never inflated by a passing strong group.
        // 1.0.1: a world with PlayerScalingEnabled=false (e.g. a fixed-difficulty dungeon) skips the group
        // delta entirely and stays at the escalated floor regardless of nearby player power.
        double difficulty = floor.effectiveFloor();
        if (settings.isPlayerScalingEnabled() && regionPower > 0.0 && !floor.insideStartRing()) {
            double scaled = ScalingEngine.resolve(
                    ScalingContext.openWorld(floor.effectiveFloor(), regionPower, MobScalingPresenceSystem.mode(settings)),
                    settings.getGroupDeltaBandWidth(), settings.getDifficultyMinCap(), settings.getDifficultyMaxCap());
            difficulty = settings.isOnlyRaiseDifficulty() ? Math.max(scaled, floor.effectiveFloor()) : scaled;
        }
        return new SpawnScaling(difficulty, floor.raritySpawnChance(), floor.zoneName(),
                floor.baseFloor(), floor.escalationBonus(), floor.effectiveFloor(), regionPower,
                floor.biomeName());
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
