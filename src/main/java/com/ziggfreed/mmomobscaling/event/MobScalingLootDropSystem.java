package com.ziggfreed.mmomobscaling.event;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDropList;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.modules.entity.damage.DeferredCorpseRemoval;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.item.ItemModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SplitMix64;
import com.ziggfreed.mmomobscaling.MobScalingPlugin;
import com.ziggfreed.mmomobscaling.component.ScaledMobComponent;
import com.ziggfreed.mmomobscaling.config.RarityConfig;
import com.ziggfreed.mmomobscaling.config.VariantConfig;
import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.scaling.MobScaleResult;
import com.ziggfreed.mmomobscaling.variant.Variant;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.joml.Vector3d;

/**
 * The LOOT half of the risk/reward loop (the XP half is {@link MobScalingXpReward}): when a RARITY mob dies,
 * pull bonus items from its rarity's native {@code ItemDropList} ({@code Rarity.bonusDropListId}, authored in
 * {@code Server/Drops/*}) and drop them at the corpse - native-asset-first, the drop TABLE is pure data an
 * owner or pack overrides by id, the mod only decides WHEN and HOW MANY pulls.
 *
 * <p>Mirrors the vanilla {@code NPCDamageSystems.DropDeathItems} shape: an {@link EntityTickingSystem} over
 * {@code ScaledMobComponent + DeathComponent} (not Player), ordered inside the corpse window
 * ({@code AFTER TickCorpseRemoval, BEFORE CorpseRemoval}), honoring the deferred-corpse delay so the bonus
 * loot appears WITH the native drops, spawning via {@code ItemComponent.generateItemDrops}. One-shot via the
 * {@link ScaledMobComponent#bonusLootDropped()} latch (the vanilla analog is {@code Role.hasDroppedDeathItems}).
 *
 * <p>The folded {@link MobScaleResult#lootMult()} is consumed HERE as the pull count: {@code floor(lootMult)}
 * guaranteed pulls plus one extra with probability {@code frac(lootMult)}, decided deterministically per mob
 * ({@link SplitMix64} off the persisted entity UUID - the convention RNG; item CONTENT stays the native
 * droplist roll). Both the rarity's AND the variant overlay's authored {@code BonusDropList}s are pulled (each
 * that count), so a variant stacks its own bonus loot on top of the rarity's. A fully plain mob (no rarity, no
 * variant) drops nothing extra. Whole body try-guarded; a loot throw must never break the death pipeline.
 */
public final class MobScalingLootDropSystem extends EntityTickingSystem<EntityStore> {

    /** Salt folded into the per-UUID seed so the pull roll decorrelates from the spawn-time rarity roll. */
    private static final long PULL_ROLL_SALT = 0x4C4F4F54524F4C4CL; // "LOOTROLL"

    /** Warn-once-per-distinct-id set for a rarity naming a BonusDropList no ItemDropList asset claims. */
    private static final Set<String> WARNED_IDS = ConcurrentHashMap.newKeySet();

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

            ItemModule itemModule = ItemModule.get();
            if (!itemModule.isEnabled()) {
                return;
            }
            int pulls = lootPulls(r.lootMult(), pullRoll(archetypeChunk, index));

            // Pull from the rarity's bonus table AND the variant's (either may be absent). The variant is an
            // independent overlay, so its authored drops stack on top of the rarity's; the pull COUNT (already
            // reflecting the variant's loot multiplier via r.lootMult()) applies to each authored list.
            List<ItemStack> itemsToDrop = new ObjectArrayList<>();
            Rarity rarity = r.hasRarity() ? RarityConfig.getInstance().resolve(r.rarityId()) : null;
            collectDrops(rarity != null ? rarity.bonusDropListId() : null, "rarity '" + r.rarityId() + "'",
                    pulls, itemModule, itemsToDrop);
            Variant variant = r.hasVariant() ? VariantConfig.getInstance().resolve(r.variantId()) : null;
            collectDrops(variant != null ? variant.bonusDropListId() : null, "variant '" + r.variantId() + "'",
                    pulls, itemModule, itemsToDrop);
            if (itemsToDrop.isEmpty()) {
                return;
            }

            TransformComponent transform = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
            HeadRotation headRotation = archetypeChunk.getComponent(index, HeadRotation.getComponentType());
            if (transform == null || headRotation == null) {
                return; // guaranteed by the query, but guard anyway
            }
            Vector3d dropPosition = new Vector3d(transform.getPosition()).add(0, 1, 0);
            Holder<EntityStore>[] drops =
                    ItemComponent.generateItemDrops(store, itemsToDrop, dropPosition, new Rotation3f(headRotation.getRotation()));
            commandBuffer.addEntities(drops, AddReason.SPAWN);
        } catch (Throwable t) {
            safeWarn("bonus loot drop failed: " + t);
        }
    }

    /**
     * Pull {@code pulls} times from the native {@code ItemDropList} named {@code dropListId} (an authored
     * rarity/variant bonus table) into {@code out}. A null/blank id is a legit "no bonus table" choice (no-op);
     * an id no {@code ItemDropList} asset claims warns once (attributed to {@code owner}) and drops nothing.
     */
    private static void collectDrops(@Nullable String dropListId, @Nonnull String owner, int pulls,
            @Nonnull ItemModule itemModule, @Nonnull List<ItemStack> out) {
        if (dropListId == null || dropListId.isEmpty()) {
            return;
        }
        if (ItemDropList.getAssetMap().getAsset(dropListId) == null) {
            if (WARNED_IDS.add(dropListId)) {
                safeWarn(owner + " names BonusDropList '" + dropListId
                        + "' but no ItemDropList asset claims that id; no bonus loot will drop");
            }
            return;
        }
        for (int i = 0; i < pulls; i++) {
            out.addAll(itemModule.getRandomItemDrops(dropListId));
        }
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
