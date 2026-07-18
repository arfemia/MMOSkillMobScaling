package com.ziggfreed.mmomobscaling.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.modules.entity.damage.DeferredCorpseRemoval;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.instance.reward.GrantOutcome;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.instance.reward.InstanceRewardGranter;
import com.ziggfreed.common.instance.reward.LootEntry;
import com.ziggfreed.common.instance.reward.NativeLootService;
import com.ziggfreed.common.util.SplitMix64;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.component.ScaledMobComponent;
import com.ziggfreed.mmomobscaling.config.RarityConfig;
import com.ziggfreed.mmomobscaling.config.VariantConfig;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.reward.MobScalingRewardSink;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;
import com.ziggfreed.mmomobscaling.variant.Variant;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.joml.Vector3d;

/**
 * The LOOT half of the risk/reward loop (the XP half is {@link MobScalingXpReward}): when a RARITY mob dies,
 * pull bonus items from its rarity's native {@code ItemDropList} ({@code Rarity.bonusDropListId}, authored in
 * {@code Server/Drops/*}) and drop them at the corpse - native-asset-first, the drop TABLE is pure data an
 * owner or pack overrides by id, the mod only decides WHEN and HOW MANY pulls. The native roll + in-world
 * spawn PLUMBING itself lives in ziggfreed-common's {@link NativeLootService} (P4 consolidation - this
 * system keeps only the pull-COUNT policy, the per-mob seed, and the rarity/variant bonus-table selection).
 *
 * <p>Mirrors the vanilla {@code NPCDamageSystems.DropDeathItems} shape: an {@link EntityTickingSystem} over
 * {@code ScaledMobComponent + DeathComponent} (not Player), ordered inside the corpse window
 * ({@code AFTER TickCorpseRemoval, BEFORE CorpseRemoval}), honoring the deferred-corpse delay so the bonus
 * loot appears WITH the native drops. One-shot via the {@link ScaledMobComponent#bonusLootDropped()} latch
 * (the vanilla analog is {@code Role.hasDroppedDeathItems}).
 *
 * <p>The folded {@link MobScaleResult#lootMult()} is consumed HERE as the pull count: {@code floor(lootMult)}
 * guaranteed pulls plus one extra with probability {@code frac(lootMult)}, decided deterministically per mob
 * ({@link SplitMix64} off the persisted entity UUID - the convention RNG; item CONTENT stays the native
 * droplist roll). Both the rarity's AND the variant overlay's authored {@code BonusDropList}s are pulled (each
 * that count), so a variant stacks its own bonus loot on top of the rarity's. A fully plain mob (no rarity, no
 * variant) drops nothing extra.
 *
 * <p><b>P4 additive reward layer:</b> a rarity/variant may also author an optional {@code BonusRewards}
 * layer (ziggfreed-common {@link LootEntry} compact specs - currency/command/token entries a native
 * {@code ItemDropList} cannot carry). When present, it is resolved as guaranteed/any (a mob kill has no
 * win/score axis to gate on) and granted to the KILLER via {@link InstanceRewardGranter} + a
 * {@link MobScalingRewardSink}, using the same corpse's {@link DeathComponent#getDeathInfo()} to resolve the
 * killer (mirrors {@code MobKillEventSystem.resolveAttackerRef} in the MMO jar). A non-player killer (mob-vs-
 * mob, environment, a turret) simply skips the reward layer; the item loot above is unaffected either way.
 * The continuous kill-XP multiplier ({@link MobScalingXpReward}) is a SEPARATE path and is untouched by this.
 *
 * <p>Whole body try-guarded; a loot/reward throw must never break the death pipeline.
 */
public final class MobScalingLootDropSystem extends EntityTickingSystem<EntityStore> {

    /** Salt folded into the per-UUID seed so the pull roll decorrelates from the spawn-time rarity roll. */
    private static final long PULL_ROLL_SALT = 0x4C4F4F54524F4C4CL; // "LOOTROLL"

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

            int pulls = lootPulls(r.lootMult(), pullRoll(archetypeChunk, index));

            // Pull from the rarity's bonus table AND the variant's (either may be absent). The variant is an
            // independent overlay, so its authored drops stack on top of the rarity's; the pull COUNT (already
            // reflecting the variant's loot multiplier via r.lootMult()) applies to each authored list.
            Rarity rarity = r.hasRarity() ? RarityConfig.getInstance().resolve(r.rarityId()) : null;
            Variant variant = r.hasVariant() ? VariantConfig.getInstance().resolve(r.variantId()) : null;

            List<ItemStack> itemsToDrop = new ObjectArrayList<>();
            pullDrops(rarity != null ? rarity.bonusDropListId() : null, pulls, itemsToDrop);
            pullDrops(variant != null ? variant.bonusDropListId() : null, pulls, itemsToDrop);

            // The P4 additive reward layer: pure data on the rarity/variant a native ItemDropList cannot
            // carry, granted to the killer alongside the bonus item loot above.
            List<LootEntry> bonusRewardSpecs = new ArrayList<>();
            if (rarity != null) {
                bonusRewardSpecs.addAll(rarity.bonusRewards());
            }
            if (variant != null) {
                bonusRewardSpecs.addAll(variant.bonusRewards());
            }

            if (itemsToDrop.isEmpty() && bonusRewardSpecs.isEmpty()) {
                return;
            }

            TransformComponent transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
            HeadRotation headRotation = archetypeChunk.getComponent(index, HeadRotation.getComponentType());
            if (transform == null || headRotation == null) {
                return; // guaranteed by the query, but guard anyway
            }

            if (!itemsToDrop.isEmpty()) {
                Vector3d dropPosition = new Vector3d(transform.getPosition()).add(0, 1, 0);
                NativeLootService.spawnInWorld(store, commandBuffer, dropPosition,
                        new Rotation3f(headRotation.getRotation()), itemsToDrop);
            }

            if (!bonusRewardSpecs.isEmpty()) {
                grantBonusRewards(archetypeChunk, index, store, bonusRewardSpecs);
            }
        } catch (Throwable t) {
            safeWarn("bonus loot drop failed: " + t);
        }
    }

    /**
     * Pull {@code pulls} times from the native {@code ItemDropList} named {@code dropListId} (an authored
     * rarity/variant bonus table) into {@code out} via the shared {@link NativeLootService} (the roll +
     * warn-once-per-unknown-id plumbing lives there now). A null/blank id is a legit "no bonus table"
     * choice (no-op).
     */
    private static void pullDrops(@Nullable String dropListId, int pulls, @Nonnull List<ItemStack> out) {
        if (dropListId == null || dropListId.isBlank()) {
            return;
        }
        for (int i = 0; i < pulls; i++) {
            out.addAll(NativeLootService.rollNative(dropListId));
        }
    }

    /**
     * Grant the P4 additive reward layer to the KILLER (see the class doc). Resolved as guaranteed/any -
     * every authored entry fires unconditionally (a mob kill has no win/score axis to gate {@link LootEntry}'s
     * pool weight/gate semantics on); skipped entirely when the killer cannot be resolved to a player.
     */
    private static void grantBonusRewards(@Nonnull ArchetypeChunk<EntityStore> archetypeChunk, int index,
            @Nonnull Store<EntityStore> store, @Nonnull List<LootEntry> specs) {
        Ref<EntityStore> killerRef = resolveKillerRef(archetypeChunk, index);
        if (killerRef == null) {
            return;
        }
        PlayerRef killerPlayerRef = store.getComponent(killerRef, PlayerRef.getComponentType());
        if (killerPlayerRef == null) {
            return; // non-player killer (mob-vs-mob, a turret, environment); no one to credit
        }
        List<InstanceReward> rewards = new ArrayList<>(specs.size());
        for (LootEntry entry : specs) {
            rewards.add(entry.resolve(ThreadLocalRandom.current()));
        }
        GrantOutcome outcome = InstanceRewardGranter.grantAll(rewards, killerPlayerRef, killerRef, store, MobScalingRewardSink.INSTANCE);
        if (outcome.anyBlocked() || outcome.failed() > 0) {
            // A COMMAND/token spec never blocks (it runs through the sink); only an ITEM spec can,
            // and items belong in the native BonusDropList, not BonusRewards. Log rather than lose it silently.
            safeWarn("BonusRewards not fully delivered to " + killerPlayerRef.getUsername()
                    + " (blocked=" + outcome.blocked() + ", failed=" + outcome.failed()
                    + "); author item rewards in the native BonusDropList, not BonusRewards");
        }
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
     * The pull count the folded loot multiplier buys: {@code floor(lootMult)} guaranteed pulls, plus one
     * extra when {@code roll01} lands under the fractional part. Non-positive mults buy nothing. Pure,
     * unit-tested.
     */
    static int lootPulls(double lootMult, double roll01) {
        if (lootMult <= 0.0) {
            return 0;
        }
        int pulls = (int) Math.floor(lootMult);
        double frac = lootMult - pulls;
        if (frac > 0.0 && roll01 < frac) {
            pulls++;
        }
        return pulls;
    }

    /**
     * A deterministic per-mob roll in {@code [0,1)} for the fractional pull, seeded off the persisted entity
     * UUID + {@link #PULL_ROLL_SALT} (the same stable-identity choice as the spawn seed; a mob always pays
     * the same pull count). Falls back to a mid-range constant when the UUID is somehow absent.
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

    private static void safeWarn(@Nonnull String message) {
        try {
            MobScalingPlugin.LOGGER.atWarning().log(message);
        } catch (Throwable ignored) {
            // log-manager-less JVMs
        }
    }
}
