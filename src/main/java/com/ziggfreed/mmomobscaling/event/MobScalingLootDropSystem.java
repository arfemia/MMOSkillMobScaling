package com.ziggfreed.mmomobscaling.event;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.modules.entity.damage.DeferredCorpseRemoval;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.command.CommandRunner;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.loot.FactorLookup;
import com.ziggfreed.common.loot.FactorSnapshot;
import com.ziggfreed.common.loot.GroundSpillSinks;
import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.LootRef;
import com.ziggfreed.common.loot.StochasticCount;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.subject.PlayerRefSubjectHandle;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SplitMix64;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.component.ScaledMobComponent;
import com.ziggfreed.mmomobscaling.config.RarityConfig;
import com.ziggfreed.mmomobscaling.config.VariantConfig;
import com.ziggfreed.mmomobscaling.factor.MobScalingFactors;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;
import com.ziggfreed.mmomobscaling.variant.Variant;

/**
 * The LOOT half of the risk/reward loop (the XP half is {@link MobScalingXpReward}): when a mob carrying a
 * RARITY or a VARIANT dies, roll whatever that tier and that overlay each authored in their own {@code Loot}
 * block and hand it over at the corpse.
 *
 * <p>The block is the shared ziggfreed-common loot vocabulary, so a tier's extra drops are written exactly
 * the way a station's rare find, a chest and a quest reward are written: shared tables by id, rolls inline,
 * and inside a roll's grants any mix of native drop tables, exact items, console commands and registered
 * reward kinds. A roll may gate itself on {@code Conditions} and scale its {@code Chance} with factors,
 * which is what makes "only for a lucky player" or "only in a hardened region" pure content - including
 * this mod's OWN readings about the mob that just died, published through
 * {@link MobScalingFactors}.
 *
 * <p>Mirrors the vanilla {@code NPCDamageSystems.DropDeathItems} shape: an {@link EntityTickingSystem} over
 * {@code ScaledMobComponent + DeathComponent} (not Player), ordered inside the corpse window
 * ({@code AFTER TickCorpseRemoval, BEFORE CorpseRemoval}), honoring the deferred-corpse delay so the bonus
 * loot appears WITH the native drops. One-shot via the {@link ScaledMobComponent#bonusLootDropped()} latch
 * (the vanilla analog is {@code Role.hasDroppedDeathItems}).
 *
 * <p><b>What the loot multiplier buys.</b> The folded {@link MobScaleResult#lootMult()} is the number of
 * PASSES over each host's block: {@code floor(lootMult)} guaranteed plus one more with probability
 * {@code frac(lootMult)}, decided deterministically per mob ({@link SplitMix64} off the persisted entity
 * UUID - the convention RNG). Each pass rolls the whole block afresh, so a tier worth double loot really
 * does pay its rolls twice; write per-pass amounts accordingly. The rarity's block and the variant's are
 * independent and both are rolled, so an overlay stacks its own finds on the base tier's.
 *
 * <p>Every reading the rolls take is resolved ONCE for the whole death (one {@link FactorSnapshot} across
 * both hosts and every pass), so two rolls asking about the same luck can never disagree. Items and native
 * drop tables spill on the GROUND at the corpse - a mob killed by anything at all still drops them - while
 * commands and registered reward kinds need a player to pay and are simply skipped when the killer is not
 * one.
 *
 * <p>Whole body try-guarded; a loot throw must never break the death pipeline.
 */
public final class MobScalingLootDropSystem extends EntityTickingSystem<EntityStore> {

    /** Salt folded into the per-UUID seed so the pull roll decorrelates from the spawn-time rarity roll. */
    private static final long PULL_ROLL_SALT = 0x4C4F4F54524F4C4CL; // "LOOTROLL"

    /**
     * The first loot multiplier whose pass count no longer fits an int ({@code 2^31}). At or past it a death
     * pays no passes at all, which is what this system has always done there.
     */
    private static final double PULLS_CEILING = 0x1p31;

    /** Warn-once set for a loot table id nothing answers to, so one typo costs one line, not one per kill. */
    private static final Set<String> WARNED_TABLES = ConcurrentHashMap.newKeySet();

    @Nonnull
    private final ComponentType<EntityStore, ScaledMobComponent> scaledType = ScaledMobComponent.getComponentType();

    @Nonnull
    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, DeathSystems.TickCorpseRemoval.class),
            new SystemDependency<>(Order.BEFORE, DeathSystems.CorpseRemoval.class));

    @Nonnull
    private final Query<EntityStore> query = Query.and(
            scaledType,
            TransformComponent.getComponentType(),
            HeadRotation.getComponentType(),
            DeathComponent.getComponentType(),
            Query.not(Player.getComponentType()));

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
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            ScaledMobComponent comp = archetypeChunk.getComponent(index, scaledType);
            if (comp == null || comp.bonusLootDropped()) {
                return;
            }
            MobScaleResult r = comp.result();
            if (!r.hasRarity() && !r.hasVariant()) {
                comp.markBonusLootDropped(); // plain mob: latch so this corpse never re-evaluates
                return;
            }

            // Match the native drop timing: wait out the deferred-corpse delay so the bonus loot
            // appears together with the role's own death drops, not seconds earlier.
            DeferredCorpseRemoval deferred = archetypeChunk.getComponent(index, DeferredCorpseRemoval.getComponentType());
            if (deferred != null && !deferred.shouldRemove()) {
                return;
            }
            comp.markBonusLootDropped();

            Rarity rarity = r.hasRarity() ? RarityConfig.getInstance().resolve(r.rarityId()) : null;
            Variant variant = r.hasVariant() ? VariantConfig.getInstance().resolve(r.variantId()) : null;
            LootRef rarityLoot = rarity != null ? rarity.loot() : null;
            LootRef variantLoot = variant != null ? variant.loot() : null;
            if (rarityLoot == null && variantLoot == null) {
                return; // neither host authored anything to hand over
            }

            int passes = lootPulls(r.lootMult(), pullRoll(archetypeChunk, index));
            if (passes <= 0) {
                return;
            }

            Ref<EntityStore> victimRef = archetypeChunk.getReferenceTo(index);
            Ref<EntityStore> killerRef = resolveKillerRef(archetypeChunk, index);
            PlayerRef killerPlayerRef = killerRef != null
                    ? store.getComponent(killerRef, PlayerRef.getComponentType()) : null;

            // ONE reading set for the whole death: the killer is the subject the readings are ABOUT,
            // the corpse is the target they happened TO, so a roll can weigh the killer's luck and the
            // mob's own rarity in the same formula without either side guessing which entity it got.
            FactorContext about = FactorContext.builder()
                    .store(store)
                    .subject(killerRef)
                    .target(victimRef)
                    .world(worldOf(store))
                    .build();
            FactorLookup lookup = new FactorSnapshot(MobScalingFactors.registry(), about);

            rollHost(rarityLoot, rarity != null ? "rarity:" + rarity.id() : "rarity", passes, lookup,
                    store, commandBuffer, victimRef, killerRef, killerPlayerRef, r);
            rollHost(variantLoot, variant != null ? "variant:" + variant.id() : "variant", passes, lookup,
                    store, commandBuffer, victimRef, killerRef, killerPlayerRef, r);
        } catch (Throwable t) {
            safeWarn("bonus loot drop failed: " + t);
        }
    }

    /** Roll ONE host's authored block {@code passes} times through the shared engine. */
    private static void rollHost(@Nullable LootRef loot, @Nonnull String label, int passes,
            @Nonnull FactorLookup lookup, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Ref<EntityStore> victimRef,
            @Nullable Ref<EntityStore> killerRef, @Nullable PlayerRef killerPlayerRef,
            @Nonnull MobScaleResult result) {
        if (loot == null) {
            return;
        }
        LootEngine.Resolved resolved = LootEngine.resolve(loot, missing -> reportUnknownTable(label, missing));
        if (resolved.rolls().isEmpty() && resolved.pools().isEmpty()) {
            return;
        }
        LootEngine.Sinks sinks = sinks(store, commandBuffer, victimRef, killerRef, killerPlayerRef,
                result, label);
        for (int pass = 0; pass < passes; pass++) {
            LootEngine.rollAndGrant(resolved.rolls(), resolved.pools(), null, lookup, Math::random, sinks);
        }
    }

    /**
     * Where a scaled mob's death loot goes: items and native drop tables spill on the ground at the corpse
     * (so a mob killed by anything at all still drops them) through the library's corpse ground
     * ({@link GroundSpillSinks#atEntity}: the victim's position and facing read when the pile drops, lifted as
     * the engine lifts a dying entity's items; this system ticks before {@code CorpseRemoval}, so the corpse is
     * still there), one pile per hand-over, every spilled stack counted as landed; commands and registered
     * reward kinds are paid to the KILLER and are wired only when the killer resolves to a player.
     */
    @Nonnull
    private static LootEngine.Sinks sinks(@Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull Ref<EntityStore> victimRef,
            @Nullable Ref<EntityStore> killerRef, @Nullable PlayerRef killerPlayerRef,
            @Nonnull MobScaleResult result, @Nonnull String label) {

        LootEngine.Sinks.Builder builder = GroundSpillSinks.atEntity(store, commandBuffer, victimRef)
                .countFailedDrops(true)
                .warn(MobScalingLootDropSystem::safeWarn)
                .build()
                .into(LootEngine.Sinks.builder())
                .sourceId("mobscaling:" + label)
                .warn(MobScalingLootDropSystem::safeWarn);

        if (killerPlayerRef != null && killerRef != null) {
            String username = killerPlayerRef.getUsername();
            Map<String, String> placeholders = new LinkedHashMap<>();
            placeholders.put("player", username == null ? "" : username);
            placeholders.put("rarity", result.rarityId());
            placeholders.put("variant", result.variantId());
            builder.commands(CommandRunner.CONSOLE, placeholders);
            builder.rewards(RewardKinds.shared(), rewardSubject(killerPlayerRef, username));
        }
        return builder.build();
    }

    /**
     * The subject a registered reward kind pays the killer through. Its handle answers for the live
     * {@link Player} as well as the {@link PlayerRef} ({@link PlayerRefSubjectHandle}): the library's
     * {@code Item}, {@code Lootable} and {@code Stamped_Item} kinds ask the subject for a
     * {@code Player}, so a bare {@code PlayerRef} handle left every one of them paying nothing.
     * Package-private and pure, so a test pins the shape without a store.
     */
    @Nonnull
    static Subject rewardSubject(@Nullable PlayerRef killer, @Nullable String username) {
        return PlayerRefSubjectHandle.subjectFor(killer, username == null ? "" : username);
    }

    /**
     * The entity that dealt the killing blow, resolved off the corpse's {@link DeathComponent#getDeathInfo()}
     * (still resident in memory here - this system ticks BEFORE {@code CorpseRemoval} removes the component,
     * mirroring {@code MobKillEventSystem.resolveAttackerRef} in the MMO jar). {@code null} for an
     * environment/command death or once the death info is already gone (e.g. a reload).
     */
    @Nullable
    private static Ref<EntityStore> resolveKillerRef(@Nonnull ArchetypeChunk<EntityStore> archetypeChunk, int index) {
        DeathComponent death = archetypeChunk.getComponent(index, DeathComponent.getComponentType());
        if (death == null) {
            return null;
        }
        Damage deathInfo = death.getDeathInfo();
        if (deathInfo == null) {
            return null;
        }
        Damage.Source source = deathInfo.getSource();
        if (source instanceof Damage.EntitySource es) {
            return es.getRef(); // covers ProjectileSource too (extends EntitySource; ref = the shooter)
        }
        return null;
    }

    /**
     * The pass count the folded loot multiplier buys: {@code floor(lootMult)} guaranteed, plus one more when
     * {@code roll01} lands under the fractional part (the library's {@link StochasticCount} rule, the per-mob
     * roll as its one draw). Non-positive and non-finite mults buy nothing, and so does a mult at or past
     * {@link #PULLS_CEILING}: a count that size is a misconfigured clamp, never two billion passes over a
     * corpse. Pure, unit-tested.
     */
    static int lootPulls(double lootMult, double roll01) {
        if (lootMult >= PULLS_CEILING) {
            return 0;
        }
        return StochasticCount.resolve(lootMult, () -> roll01);
    }

    /**
     * A deterministic per-mob roll in {@code [0,1)} for the fractional pass, seeded off the persisted entity
     * UUID + {@link #PULL_ROLL_SALT} (the same stable-identity choice as the spawn seed; a mob always pays
     * the same pass count). Falls back to a mid-range constant when the UUID is somehow absent.
     */
    private static double pullRoll(@Nonnull ArchetypeChunk<EntityStore> archetypeChunk, int index) {
        UUIDComponent uuidComp = archetypeChunk.getComponent(index, UUIDComponent.getComponentType());
        UUID uuid = uuidComp != null ? uuidComp.getUuid() : null;
        if (uuid == null) {
            return 0.5;
        }
        long seed = SplitMix64.mix(SplitMix64.mix(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()), PULL_ROLL_SALT);
        return new SplitMix64(seed).nextDouble();
    }

    /** The world behind the store, or null where the context has none. */
    @Nullable
    private static World worldOf(@Nonnull Store<EntityStore> store) {
        try {
            return store.getExternalData().getWorld();
        } catch (Throwable t) {
            return null;
        }
    }

    /** One line per distinct loot-table id nothing answers to; the rest of the pass carries on. */
    private static void reportUnknownTable(@Nonnull String label, @Nonnull String tableId) {
        if (WARNED_TABLES.add(tableId.toLowerCase(Locale.ROOT))) {
            safeWarn(label + " names loot table '" + tableId + "', which nothing answers to");
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
