package com.ziggfreed.mmomobscaling.event;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentDisplayName;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.components.SpawnMarkerReference;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.spawning.spawnmarkers.SpawnMarkerEntity;
import com.ziggfreed.common.health.HealthUtil;
import com.ziggfreed.common.util.SplitMix64;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.affix.Affix;
import com.ziggfreed.mmomobscaling.component.PendingRollComponent;
import com.ziggfreed.mmomobscaling.component.ScaledMobComponent;
import com.ziggfreed.mmomobscaling.config.MobScalingConfig;
import com.ziggfreed.mmomobscaling.config.SpawnScalingSettings;
import com.ziggfreed.mmomobscaling.family.MobFamilyMatcher;
import com.ziggfreed.mmomobscaling.i18n.MobScalingTextUtil;
import com.ziggfreed.mmomobscaling.pages.RoleBaseHealthResolver;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.rarity.RarityRoster;
import com.ziggfreed.mmomobscaling.roster.Rosters;
import com.ziggfreed.mmomobscaling.scaling.MobScaleFold;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;
import com.ziggfreed.mmomobscaling.variant.Variant;
import com.ziggfreed.mmomobscaling.world.ZoneDifficultyResolver;

/**
 * The ROLL, one tick after the spawn: the second half of the spawn lock, over the entities
 * {@link MobScalingSpawnHook} stamped a {@link PendingRollComponent} onto. It runs with a valid
 * {@code Ref}, which is what makes the two SKIPS readable that the pre-add hook could never see:
 * <ol>
 *   <li><b>A scripted spawn.</b> An NPC a {@code ManualTrigger} spawn marker raised, which is what
 *       an encounter script's {@code TriggerSpawners} fires for its boss and its adds. The engine
 *       attaches the mob's {@code SpawnMarkerReference} in a post-spawn step that runs AFTER the
 *       store's add, so it is only there on the tick. This is the load-bearing test: it answers
 *       correctly whatever order the encounter's own tick binds the subject in.</li>
 *   <li><b>An encounter's bound subject.</b> {@code EncounterRuntime.isBoundSubject}, a lock-free
 *       index read the encounter tick refreshes, keyed by {@code Ref}. Secondary on purpose: a boss
 *       can exist for a tick or two before it is indexed, and a re-added holder mints a key the index
 *       does not yet hold; it earns its place on the reload and re-add paths.</li>
 * </ol>
 * A skipped mob is left exactly as the engine and the encounter made it, any stale
 * {@code mmoscaling_hp} modifier and {@code Mmoscaling_*} aura from an earlier save stripped, and
 * carries no {@link ScaledMobComponent} at all.
 *
 * <p>What the roll decides and how it decides it is the same as it always was: the same
 * {@code SplitMix64} seed off the entity's uuid and the world seed, the same rosters, the same family
 * and pool gates, the same fold; a given mob gets the same result. What moves is WHEN: the
 * {@link ScaledMobComponent} stamp, the display-name decoration, the HP reconcile, the aura and affix
 * effects and the caster kit all land on the first tick after the add rather than inside it. The one
 * visible artefact is that a freshly spawned mob enters the world at its base maximum health and is
 * reconciled to its scaled maximum a tick later (the first apply heals to the new max).
 *
 * <p>A component added to a LIVE entity moves it to another archetype chunk without re-adding it,
 * so neither {@code MobScalingEffectApplySystem} nor {@code MobScalingCasterArmSystem} (both
 * {@code RefSystem}s on the scaled archetype) sees the stamp; this system calls their shared bodies
 * directly. The pending marker is removed on every path, so an entity is decided exactly once per
 * add.
 *
 * <p>Three INFO lines per boot, one per path the first time it runs (rolled after the deferral,
 * skipped as a scripted spawn, skipped as a bound subject); never per mob. Whole body try-guarded.
 */
public final class MobScalingRollSystem extends EntityTickingSystem<EntityStore> {

    private static final AtomicBoolean REPORTED_ROLL = new AtomicBoolean();
    private static final AtomicBoolean REPORTED_SCRIPTED = new AtomicBoolean();
    private static final AtomicBoolean REPORTED_BOUND = new AtomicBoolean();

    @Nonnull
    private final ComponentType<EntityStore, PendingRollComponent> pendingType = PendingRollComponent.getComponentType();
    @Nonnull
    private final ComponentType<EntityStore, ScaledMobComponent> scaledType = ScaledMobComponent.getComponentType();
    @Nonnull
    private final ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
    @Nonnull
    private final ComponentType<EntityStore, EntityStatMap> statType = EntityStatMap.getComponentType();

    @Nonnull
    private final Query<EntityStore> query = pendingType;

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> cb) {
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        String failRole = "?";
        try {
            // Decided this tick whatever happens below: the marker never survives a second look.
            cb.removeComponent(ref, pendingType);
            PendingRollComponent pending = archetypeChunk.getComponent(index, pendingType);
            NPCEntity npc = store.getComponent(ref, npcType);
            World world = store.getExternalData().getWorld();
            if (pending == null || npc == null || world == null) {
                return;
            }
            String roleName = npc.getRoleName();
            if (roleName != null) {
                failRole = roleName;
            }
            if (isScriptedSpawn(store, ref)) {
                leaveAlone(store, ref, cb);
                reportOnce(REPORTED_SCRIPTED, "deferred roll: " + failRole + " rose from a ManualTrigger spawn marker "
                        + "(a scripted spawn), so it is left to its script; every later scripted spawn is skipped the "
                        + "same way, unreported");
                return;
            }
            if (EncounterBinding.isBoundSubject(store, ref)) {
                leaveAlone(store, ref, cb);
                reportOnce(REPORTED_BOUND, "deferred roll: " + failRole + " is the bound subject of a live encounter, "
                        + "so it is left to the encounter framework; every later bound subject is skipped the same "
                        + "way, unreported");
                return;
            }
            roll(store, ref, cb, npc, world, pending.scope());
            reportOnce(REPORTED_ROLL, "deferred roll: " + failRole + " was rolled one tick after its spawn, on its "
                    + "own ref, with the scripted-spawn and bound-subject skips live; every later roll runs the "
                    + "same way, unreported");
        } catch (Throwable t) {
            safeWarn("deferred spawn scale failed for role " + failRole + ": " + t);
        }
    }

    /**
     * Whether the mob was raised by a {@code ManualTrigger} spawn marker: the mob's own marker
     * reference, followed to the marker entity, asked for its authored flag. The reference is a
     * codec-backed component on the holder, so it survives an in-place role change and answers the
     * same on the re-add. Anything unreadable answers false (an ordinary spawn).
     */
    private static boolean isScriptedSpawn(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            SpawnMarkerReference reference = store.getComponent(ref, SpawnMarkerReference.getComponentType());
            if (reference == null || reference.getReference() == null) {
                return false;
            }
            Ref<EntityStore> markerRef = reference.getReference().getEntity(store);
            if (markerRef == null || !markerRef.isValid()) {
                return false;
            }
            SpawnMarkerEntity marker = store.getComponent(markerRef, SpawnMarkerEntity.getComponentType());
            return marker != null && marker.isManualTrigger();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The skip: strip whatever an earlier life of this mod left on the entity (a saved
     * {@code mmoscaling_hp} modifier, a saved {@code Mmoscaling_*} aura), stamp nothing. A boss the
     * encounter owns keeps exactly the stats its script and the framework gave it. (A pending mob never
     * carries a {@link ScaledMobComponent}: the hook stamps the marker only on a holder without one.)
     */
    private static void leaveAlone(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull CommandBuffer<EntityStore> cb) {
        HealthUtil.reconcileMaxHealth(store, ref, 1.0, MobScalingSpawnHook.HP_KEY);
        EffectControllerComponent ctrl = cb.getComponent(ref, EffectControllerComponent.getComponentType());
        if (ctrl != null) {
            MobScalingEffectApplySystem.sweepAll(ref, ctrl, cb);
        }
    }

    /** The roll itself, unchanged in what it decides; see the class javadoc for what moved. */
    private void roll(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull CommandBuffer<EntityStore> cb, @Nonnull NPCEntity npc, @Nonnull World world, byte scope) {
        MobScalingConfig cfg = MobScalingConfig.getInstance();
        SpawnScalingSettings spawn = cfg.spawnSettingsFor(world);
        MobScalingSpawnHook.SpawnScaling scaling = resolveSpawnScaling(world, store, ref, spawn);
        double effDifficulty = scaling.difficulty();

        SplitMix64 rng = new SplitMix64(seedFor(npc, world, store, ref));

        // The per-mob family gate: narrows which rarity tiers / variant overlays may apply to THIS mob (an
        // authored Families allow/deny block resolved against the mob's role name + native NPCGroup
        // membership), ANDed with the per-world Pool allow/deny gate. Both are pure functions of stable
        // identity/config, so they consume no RNG and keep the roll deterministic.
        Predicate<Rarity> rarityFamilyEligible = r -> spawn.isRarityAllowed(r.id())
                && MobFamilyMatcher.get().eligible(r.familyFilter(), npc);
        Predicate<Variant> variantFamilyEligible = v -> spawn.isVariantAllowed(v.id())
                && MobFamilyMatcher.get().eligible(v.familyFilter(), npc);

        // FORCED tier resolution (config-driven, no Java-side special case): a rarity whose authored
        // Families.ForceGroups / Families.ForceRoles match this mob is granted regardless of weight,
        // difficulty band, or spawn chance - the mechanism the shipped Rarities/Boss.json uses to claim
        // the Mmoscaling_Bosses NPCGroup. It consumes no RNG, and it is a FLOOR: a normal roll landing on
        // a stronger tier still wins.
        Rarity forced = Rosters.rarity().forced(r -> MobFamilyMatcher.get().forces(r.familyFilter(), npc)
                && spawn.isRarityAllowed(r.id()));
        Rarity rolled = Rosters.rarity()
                .pick(effDifficulty, scaling.raritySpawnChance(), rng, rarityFamilyEligible);
        Rarity rarity = RarityRoster.strongerOf(rolled, forced);
        // The SECOND axis: an independent family-gated variant OVERLAY (at most one), rolled after the base
        // rarity. Draws exactly once so the seed->result mapping stays stable.
        String baseRarityId = rarity != null ? rarity.id() : "";
        Variant variant = Rosters.variant().pick(effDifficulty, baseRarityId,
                spawn.getVariantChanceMultiplier(), rng, variantFamilyEligible);

        // Affixes come from BOTH hosts (rarity slots + variant slots) plus the per-world extra slots, one
        // combined distinct roll sharing the used-set + single-resistance cap; the per-world Pool.Affixes
        // allow/deny gates every draw.
        List<Affix> affixes = Rosters.affix().pick(effDifficulty, rarity, variant,
                spawn.getExtraAffixSlots(), a -> spawn.isAffixAllowed(a.id()), rng);

        MobScaleFold.DifficultyStatCurve curve = spawn.statCurveModel();
        MobScaleResult result = MobScaleFold.fold(rarity, variant, affixes, effDifficulty, scope, curve);
        // IDEMPOTENT stamp (putComponent, never addComponent), deferred through the command buffer so the
        // archetype move lands after this tick's iteration.
        cb.putComponent(ref, scaledType, new ScaledMobComponent(result));

        if (rarity != null || variant != null) {
            decorateDisplayName(store, ref, cb, rarity, variant);
        }

        // Observed-spawn ground truth for the admin-page preview: the CURRENT Health-stat max, read BEFORE
        // this system's own modifier lands below.
        recordObservedBaseHealth(npc, store, ref);

        // HP: RECONCILE the mmoscaling_hp MAX modifier to the fresh hpMult on the live stat map (hpMult==1
        // removes any prior modifier; a shrink auto-clamps current HP; the first apply heals to the new max).
        HealthUtil.reconcileMaxHealth(store, ref, result.hpMult(), MobScalingSpawnHook.HP_KEY);

        // The two RefSystems on the scaled archetype do not fire for a stamp onto a live entity, so their
        // shared bodies run from here, on the same command buffer.
        MobScalingEffectApplySystem.reconcile(ref, result, cb);
        MobScalingCasterArmSystem.arm(ref, result, cb);
    }

    /**
     * The {@code Ref} form of the spawn-scaling resolve. A missing {@code TransformComponent} degrades
     * to the world baseline floor + the un-boosted chance (no position = no zone, no escalation, no
     * group delta).
     */
    @Nonnull
    private static MobScalingSpawnHook.SpawnScaling resolveSpawnScaling(@Nonnull World world,
            @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull SpawnScalingSettings settings) {
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            double floor = settings.getDifficultyFloor();
            return new MobScalingSpawnHook.SpawnScaling(floor, settings.getRaritySpawnChance(),
                    ZoneDifficultyResolver.NO_ZONE, floor, 0.0, floor, 0.0, ZoneDifficultyResolver.NO_ZONE,
                    false, false, 0.0);
        }
        return MobScalingSpawnHook.resolveSpawnScaling(world,
                ChunkUtil.chunkCoordinate(transform.getPosition().x),
                ChunkUtil.chunkCoordinate(transform.getPosition().z), settings);
    }

    /**
     * Feed {@link RoleBaseHealthResolver} a ground-truth base-health reading for this role: the
     * {@code Health} stat's CURRENT max, read right before the {@code mmoscaling_hp} modifier is
     * applied, so it is the native-balanced base PLUS whatever any earlier-ordered mod's own modifier
     * already stacked on, never this mod's own key. A RELOADED already-scaled mob still carries the
     * persisted modifier at this point (its max is post-scale, not base) and is skipped; fresh spawns
     * supply the true reading. Best-effort, fully try-guarded: a display-only diagnostic never gates
     * gameplay.
     */
    private void recordObservedBaseHealth(@Nonnull NPCEntity npc, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref) {
        try {
            String roleName = npc.getRoleName();
            if (roleName == null) {
                return;
            }
            EntityStatMap stats = store.getComponent(ref, statType);
            if (stats == null) {
                return;
            }
            EntityStatValue health = stats.get(DefaultEntityStatTypes.getHealth());
            if (health == null || health.getModifier(MobScalingSpawnHook.HP_KEY) != null) {
                return;
            }
            RoleBaseHealthResolver.recordObserved(roleName, Math.round(health.getMax()));
        } catch (Throwable ignored) {
            // best-effort: the preview simply falls back to RoleBaseHealthResolver's template read
        }
    }

    /**
     * Stamp the rarity/variant-decorated display name (surfaces in DEATH MESSAGES / kill feed / logs;
     * the engine does not render {@code DisplayNameComponent} as an overhead nameplate). Composes
     * localized FRAME keys with NESTED client-resolved {@code Message} params, never a joined
     * English-order string, so every locale reorders the frame its own way: the rarity frame
     * {@code mmomobscaling.name.decorated} ({@code {rarity} {base}}) wraps the base, then the variant
     * frame {@code mmomobscaling.name.variant_decorated} ({@code {variant} {inner}}) wraps THAT. Reads
     * the base name {@code RoleBuilderSystem} stamped at the add, so a reload never double-decorates.
     * SKIPS a mob carrying {@code PersistentDisplayName} (a player-authored custom name is never
     * overwritten), the same guard {@code RoleBuilderSystem} itself uses.
     */
    private static void decorateDisplayName(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull CommandBuffer<EntityStore> cb, @Nullable Rarity rarity, @Nullable Variant variant) {
        if (store.getComponent(ref, PersistentDisplayName.getComponentType()) != null) {
            return;
        }
        DisplayNameComponent existing = store.getComponent(ref, DisplayNameComponent.getComponentType());
        Message base = existing != null ? existing.getDisplayName() : null;
        if (base == null) {
            return; // no base name to decorate (nameless archetype)
        }
        Message decorated = base;
        if (rarity != null) {
            decorated = Message.translation("mmomobscaling.name.decorated")
                    .param("rarity", Message.translation(MobScalingTextUtil.rarityNameKey(rarity)))
                    .param("base", decorated);
        }
        if (variant != null) {
            decorated = Message.translation("mmomobscaling.name.variant_decorated")
                    .param("variant", Message.translation(MobScalingTextUtil.variantNameKey(variant)))
                    .param("inner", decorated);
        }
        cb.putComponent(ref, DisplayNameComponent.getComponentType(), new DisplayNameComponent(decorated));
    }

    /**
     * A restart-STABLE, per-ENTITY deterministic seed: the entity's UUID folded with the world seed.
     * The UUID is persisted with the entity and restored UNCHANGED on chunk reload / restart, so the
     * SAME mob re-rolls the SAME rarity/affixes/mults every time. Falls back to a stable per-role seed
     * only if the UUID is somehow absent.
     */
    private static long seedFor(@Nonnull NPCEntity npc, @Nonnull World world, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref) {
        long worldSeed = world.getWorldConfig().getSeed();
        UUIDComponent uuidComp = store.getComponent(ref, UUIDComponent.getComponentType());
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

    private static void reportOnce(@Nonnull AtomicBoolean latch, @Nonnull String message) {
        if (latch.compareAndSet(false, true)) {
            safeInfo(message);
        }
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
